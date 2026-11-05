package de.tetnavigator.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import de.tetnavigator.core.offlinedata.InstalledPackage
import de.tetnavigator.core.offlinedata.OfflineStorage
import de.tetnavigator.core.preferences.SettingsRepository
import kotlinx.coroutines.CancellationException

/**
 * Lädt alle Pakete der gewählten Länder als Foreground-Arbeit mit
 * Fortschritts-Benachrichtigung. Nach Erfolg: offlineLocked = true.
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val settings: SettingsRepository,
    private val storage: OfflineStorage,
    private val downloader: PackageDownloader,
    private val direct: DirectInstaller,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (inputData.getString(KEY_MODE) == MODE_DIRECT) return doDirect()
        val countries = inputData.getStringArray(KEY_COUNTRIES)?.toSet().orEmpty()
        val s = settings.current()
        if (!settings.networkAllowed()) return fail(OfflineLockedException().message!!)
        val manifest = ManifestCache.load(applicationContext) ?: try {
            downloader.fetchManifest(s.baseUrl)
        } catch (e: Exception) {
            return fail(e.message ?: "Manifest-Fehler")
        }

        val plan = DownloadPlan.create(manifest, countries) { cc, f -> storage.fileFor(cc, f) to storage.partFileFor(cc, f) }
        if (!plan.fitsInto(storage.freeBytes())) {
            return fail("Zu wenig Speicher: ${plan.requiredFree() / 1_000_000} MB nötig, ${storage.freeBytes() / 1_000_000} MB frei")
        }

        try {
            setForeground(foregroundInfo("Starte…", 0, 0))
        } catch (_: Exception) { /* z. B. in Tests ohne Foreground-Erlaubnis */ }

        val total = plan.totalBytes
        var doneBefore = 0L
        val installed = ArrayList<InstalledPackage>()
        try {
            plan.files.forEachIndexed { i, pf ->
                val url = PackageDownloader.resolve(s.baseUrl, pf.file.url ?: pf.file.file)
                var lastPct = -1
                downloader.download(url, pf.part, pf.target, pf.file.bytes, pf.file.sha256) { done, _ ->
                    val overall = doneBefore + done
                    val pct = if (total > 0) (overall * 100 / total).toInt() else 0
                    if (pct != lastPct) {
                        lastPct = pct
                        setProgressAsync(
                            workDataOf(
                                P_FILE to pf.target.name, P_INDEX to i + 1, P_COUNT to plan.files.size,
                                P_DONE to overall, P_TOTAL to total,
                            ),
                        )
                        notify(foregroundInfo("${pf.country} · ${pf.target.name}", pct, 100))
                    }
                }
                doneBefore += pf.file.bytes
                installed += InstalledPackage(pf.country, pf.file.type, pf.file.file, pf.target.length(), pf.file.sha256, System.currentTimeMillis())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (installed.isNotEmpty()) storage.register(installed)
            return if (runAttemptCount < 3 && e !is OfflineLockedException) Result.retry() else fail(e.message ?: "Download-Fehler")
        }

        storage.register(installed)
        settings.lockOffline(storage.installedCountries())
        return Result.success()
    }

    /** Einrichtung ohne PC: alles direkt aus öffentlichen Quellen. */
    private suspend fun doDirect(): Result {
        if (!settings.networkAllowed()) return fail(OfflineLockedException().message!!)
        val opts = DirectOptions(
            countries = inputData.getStringArray(KEY_COUNTRIES)?.toSet().orEmpty(),
            maxZoom = inputData.getInt(KEY_MAX_ZOOM, 13),
            dem = inputData.getBoolean(KEY_DEM, true),
        )
        try { setForeground(foregroundInfo("Starte…", 0, 0)) } catch (_: Exception) { }
        var lastPct = -1
        try {
            direct.install(opts, inputData.getString(KEY_BUILD)) { p ->
                val pct = (p.fraction * 100).toInt()
                if (pct != lastPct || p.bytesTotal > 0) {
                    setProgressAsync(workDataOf(P_FILE to p.step, P_INDEX to 0, P_COUNT to 0, P_DONE to p.bytesDone, P_TOTAL to p.bytesTotal, P_FRACTION to p.fraction))
                    if (pct != lastPct) notify(foregroundInfo(p.step, pct, 100))
                    lastPct = pct
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return if (runAttemptCount < 3 && e !is OfflineLockedException) Result.retry() else fail(e.message ?: "Download-Fehler")
        }
        settings.lockOffline(storage.installedCountries())
        return Result.success()
    }

    private fun fail(msg: String) = Result.failure(workDataOf(KEY_ERROR to msg))

    private fun notify(info: ForegroundInfo) {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        try { nm.notify(NOTIFICATION_ID, info.notification) } catch (_: SecurityException) { }
    }

    private fun foregroundInfo(text: String, progress: Int, max: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Paket-Download", NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Offline-Pakete werden geladen")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(max, progress, max == 0)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val KEY_COUNTRIES = "countries"
        const val KEY_MODE = "mode"
        const val MODE_DIRECT = "direct"
        const val KEY_MAX_ZOOM = "maxZoom"
        const val KEY_DEM = "dem"
        const val KEY_BUILD = "build"
        const val P_FRACTION = "fraction"
        const val KEY_ERROR = "error"
        const val P_FILE = "file"
        const val P_INDEX = "index"
        const val P_COUNT = "count"
        const val P_DONE = "done"
        const val P_TOTAL = "total"
        private const val CHANNEL = "download"
        private const val NOTIFICATION_ID = 4711
    }
}
