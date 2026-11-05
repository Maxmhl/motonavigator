package de.tetnavigator.data.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.tetnavigator.core.offlinedata.Manifest
import de.tetnavigator.core.offlinedata.OfflineStorage
import de.tetnavigator.core.preferences.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

sealed interface DownloadState {
    data object Idle : DownloadState
    data object Queued : DownloadState
    data class Running(
        val fileName: String,
        val fileIndex: Int,
        val fileCount: Int,
        val bytesDone: Long,
        val bytesTotal: Long,
        /** Gesamtfortschritt 0..1, falls bekannt (Direkt-Modus). */
        val overall: Float? = null,
    ) : DownloadState {
        val fraction: Float get() = overall ?: if (bytesTotal <= 0) 0f else (bytesDone.toDouble() / bytesTotal).toFloat()
    }
    data class Failed(val message: String) : DownloadState
    data object Done : DownloadState
}

/** Öffentliche Schnittstelle des Download-Moduls für Einrichtung und Einstellungen. */
@Singleton
class DownloadController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val storage: OfflineStorage,
    private val downloader: PackageDownloader,
    private val direct: DirectInstaller,
) {
    private val wm get() = WorkManager.getInstance(context)

    /** Manifest vom Server des Nutzers laden (nur solange nicht offlineLocked). */
    suspend fun fetchManifest(baseUrl: String): Manifest {
        val m = downloader.fetchManifest(baseUrl)
        ManifestCache.store(context, m)
        return m
    }

    fun plan(manifest: Manifest, countries: Collection<String>): DownloadPlan =
        DownloadPlan.create(manifest, countries) { cc, f -> storage.fileFor(cc, f) to storage.partFileFor(cc, f) }

    fun freeBytes(): Long = storage.freeBytes()

    suspend fun start(countries: Set<String>, wifiOnly: Boolean) {
        settings.update { it.copy(selectedCountries = countries, wifiOnly = wifiOnly, setupMode = "server") }
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build(),
            )
            .setInputData(workDataOf(DownloadWorker.KEY_COUNTRIES to countries.toTypedArray()))
            .addTag(WORK_NAME)
            .build()
        wm.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, req)
    }

    /** Größe für die Einrichtung ohne PC berechnen (lädt nur Verzeichnisse, wenige MB). */
    suspend fun estimateDirect(options: DirectOptions): DirectEstimate = direct.estimate(options)

    suspend fun startDirect(options: DirectOptions, build: String?, wifiOnly: Boolean) {
        settings.update {
            it.copy(
                selectedCountries = options.countries, wifiOnly = wifiOnly, setupMode = "direct",
                directMaxZoom = options.maxZoom, directDem = options.dem,
            )
        }
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_MODE to DownloadWorker.MODE_DIRECT,
                    DownloadWorker.KEY_COUNTRIES to options.countries.toTypedArray(),
                    DownloadWorker.KEY_MAX_ZOOM to options.maxZoom,
                    DownloadWorker.KEY_DEM to options.dem,
                    DownloadWorker.KEY_BUILD to build,
                ),
            )
            .addTag(WORK_NAME)
            .build()
        wm.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, req)
    }

    fun cancel() { wm.cancelUniqueWork(WORK_NAME) }

    val state: Flow<DownloadState> = wm.getWorkInfosForUniqueWorkFlow(WORK_NAME).map { infos ->
        val info = infos.firstOrNull() ?: return@map DownloadState.Idle
        when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadState.Queued
            WorkInfo.State.RUNNING -> {
                val p = info.progress
                DownloadState.Running(
                    p.getString(DownloadWorker.P_FILE) ?: "",
                    p.getInt(DownloadWorker.P_INDEX, 0),
                    p.getInt(DownloadWorker.P_COUNT, 0),
                    p.getLong(DownloadWorker.P_DONE, 0),
                    p.getLong(DownloadWorker.P_TOTAL, 0),
                    p.getFloat(DownloadWorker.P_FRACTION, -1f).takeIf { it >= 0f },
                )
            }
            WorkInfo.State.SUCCEEDED -> DownloadState.Done
            WorkInfo.State.FAILED -> DownloadState.Failed(info.outputData.getString(DownloadWorker.KEY_ERROR) ?: "Unbekannter Fehler")
            WorkInfo.State.CANCELLED -> DownloadState.Failed("Abgebrochen")
        }
    }

    companion object {
        const val WORK_NAME = "package-download"
    }
}

/** Zuletzt geladenes Manifest lokal merken (der Worker braucht es ohne erneuten Request). */
internal object ManifestCache {
    private fun file(context: Context) = java.io.File(context.filesDir, "manifest.json")
    fun store(context: Context, m: Manifest) =
        file(context).writeText(Manifest.json.encodeToString(Manifest.serializer(), m))
    fun load(context: Context): Manifest? = file(context).takeIf { it.exists() }?.let { Manifest.parse(it.readText()) }
}

@Module
@InstallIn(SingletonComponent::class)
object DownloadModule {
    @Provides @Singleton
    fun policy(impl: SettingsNetworkPolicy): NetworkPolicy = impl

    @Provides @Singleton
    fun client(policy: NetworkPolicy): okhttp3.OkHttpClient = HttpClients.create(policy)

    @Provides @Singleton
    fun downloader(client: okhttp3.OkHttpClient): PackageDownloader = PackageDownloader(client)
}
