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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

sealed interface DownloadState {
    data object Idle : DownloadState
    /** Eingereiht, wartet z. B. auf WLAN. */
    data class Queued(val wifiOnly: Boolean) : DownloadState
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
    /** Vom Nutzer pausiert – bereits Geladenes bleibt erhalten. */
    data class Paused(val step: String, val fraction: Float) : DownloadState
    data class Failed(val message: String) : DownloadState
    data object Done : DownloadState

    /** Gibt es einen Auftrag, den man pausieren/fortsetzen/stoppen kann? */
    val active: Boolean get() = this is Queued || this is Running || this is Paused || this is Failed
}

/** Gespeicherter Download-Auftrag (damit Pause/Fortsetzen ohne Assistent geht). */
@kotlinx.serialization.Serializable
data class DownloadRequest(
    val mode: String,
    val countries: Set<String>,
    val wifiOnly: Boolean,
    val maxZoom: Int = 13,
    val dem: Boolean = true,
    val build: String? = null,
) {
    fun encode(): String = Manifest.json.encodeToString(serializer(), this)

    companion object {
        const val MODE_SERVER = "server"
        const val MODE_DIRECT = DownloadWorker.MODE_DIRECT
        fun decode(s: String): DownloadRequest? = if (s.isBlank()) null else runCatching { Manifest.json.decodeFromString(serializer(), s) }.getOrNull()
    }
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
        launch(DownloadRequest(DownloadRequest.MODE_SERVER, countries, wifiOnly))
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
        launch(DownloadRequest(DownloadRequest.MODE_DIRECT, options.countries, wifiOnly, options.maxZoom, options.dem, build))
    }

    private suspend fun launch(r: DownloadRequest) {
        settings.update { it.copy(activeDownload = r.encode(), downloadPaused = false, downloadPausedStep = "", downloadPausedFraction = 0.0) }
        val data = if (r.mode == DownloadRequest.MODE_DIRECT) {
            workDataOf(
                DownloadWorker.KEY_MODE to DownloadWorker.MODE_DIRECT,
                DownloadWorker.KEY_COUNTRIES to r.countries.toTypedArray(),
                DownloadWorker.KEY_MAX_ZOOM to r.maxZoom,
                DownloadWorker.KEY_DEM to r.dem,
                DownloadWorker.KEY_BUILD to r.build,
            )
        } else {
            workDataOf(DownloadWorker.KEY_COUNTRIES to r.countries.toTypedArray())
        }
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (r.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setInputData(data)
            .addTag(WORK_NAME)
            .build()
        wm.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, req)
    }

    /**
     * Pausieren: Arbeit beenden, Teildaten behalten (Karten-Fortschritt, .part-Dateien,
     * geladene Höhenkacheln). [fortsetzen] macht genau dort weiter.
     */
    suspend fun pause(current: DownloadState) {
        val (step, fraction) = when (current) {
            is DownloadState.Running -> current.fileName to current.fraction
            is DownloadState.Paused -> current.step to current.fraction
            else -> "" to 0f
        }
        settings.update { it.copy(downloadPaused = true, downloadPausedStep = step, downloadPausedFraction = fraction.toDouble()) }
        wm.cancelUniqueWork(WORK_NAME)
    }

    /** Pausierten oder fehlgeschlagenen Auftrag fortsetzen. */
    suspend fun resume(): Boolean {
        val r = DownloadRequest.decode(settings.current().activeDownload) ?: return false
        // Build fest beibehalten, damit der Karten-Fortschritt passt
        launch(r)
        return true
    }

    /** Stoppen: Auftrag verwerfen und Teildaten löschen. Fertige Pakete bleiben installiert. */
    suspend fun stop() {
        wm.cancelUniqueWork(WORK_NAME)
        settings.update { it.copy(activeDownload = "", downloadPaused = false, downloadPausedStep = "", downloadPausedFraction = 0.0) }
        storage.discardPartialDownloads()
    }

    /** Für die Abwärtskompatibilität der Einrichtung. */
    fun cancel() { wm.cancelUniqueWork(WORK_NAME) }

    val request: Flow<DownloadRequest?> = settings.settings.map { DownloadRequest.decode(it.activeDownload) }

    val state: Flow<DownloadState> = combine(wm.getWorkInfosForUniqueWorkFlow(WORK_NAME), settings.settings) { infos, s ->
        val info = infos.firstOrNull()
        val hasRequest = s.activeDownload.isNotBlank()
        when {
            s.downloadPaused && hasRequest -> DownloadState.Paused(s.downloadPausedStep, s.downloadPausedFraction.toFloat())
            info == null -> DownloadState.Idle
            else -> when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadState.Queued(s.wifiOnly)
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
                WorkInfo.State.FAILED ->
                    if (hasRequest) DownloadState.Failed(info.outputData.getString(DownloadWorker.KEY_ERROR) ?: "Unbekannter Fehler") else DownloadState.Idle
                // Abgebrochen ohne Pause = gestoppt
                WorkInfo.State.CANCELLED -> DownloadState.Idle
            }
        }
    }.distinctUntilChanged()

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
