package de.tetnavigator.data.download

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import de.tetnavigator.core.offlinedata.CountryShapes
import de.tetnavigator.core.offlinedata.OfflineStorage
import de.tetnavigator.core.preferences.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Pausieren / Fortsetzen / Stoppen eines Download-Auftrags. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadControllerTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var settings: SettingsRepository
    private lateinit var storage: OfflineStorage
    private lateinit var controller: DownloadController

    @Before fun setUp() {
        // Ohne Executor: Arbeit bleibt eingereiht (kein echter Download im Test)
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
            File(ctx.cacheDir, "dl-test-${System.nanoTime()}.preferences_pb")
        })
        storage = OfflineStorage(ctx)
        val client = HttpClients.create { true }
        controller = DownloadController(ctx, settings, storage, PackageDownloader(client), DirectInstaller(client, storage, CountryShapes(ctx)))
    }

    private suspend fun state(pred: (DownloadState) -> Boolean) = withTimeout(5_000) { controller.state.first(pred) }

    @Test fun pauseResumeAndStop() = runBlocking {
        controller.startDirect(DirectOptions(setOf("LU"), maxZoom = 12), build = "20261104.pmtiles", wifiOnly = true)
        val queued = state { it is DownloadState.Queued }
        assertThat((queued as DownloadState.Queued).wifiOnly).isTrue()
        val req = controller.request.first()!!
        assertThat(req.countries).containsExactly("LU")
        assertThat(req.build).isEqualTo("20261104.pmtiles")

        // Teildaten anlegen, die beim Pausieren erhalten bleiben müssen
        val part = File(storage.workDir("map-LU"), "tiles.data").apply { writeText("teil") }
        controller.pause(queued)
        val paused = state { it is DownloadState.Paused }
        assertThat(paused.active).isTrue()
        assertThat(part.exists()).isTrue()
        assertThat(settings.current().activeDownload).isNotEmpty()

        controller.resume()
        state { it is DownloadState.Queued }
        assertThat(controller.request.first()!!.build).isEqualTo("20261104.pmtiles") // gleicher Build → Fortsetzung passt

        controller.stop()
        state { it == DownloadState.Idle }
        assertThat(controller.request.first()).isNull()
        assertThat(part.exists()).isFalse()
    }

    @Test fun requestRoundTrip() {
        val r = DownloadRequest(DownloadRequest.MODE_DIRECT, setOf("NO", "SE"), false, 14, false, "b.pmtiles")
        assertThat(DownloadRequest.decode(r.encode())).isEqualTo(r)
        assertThat(DownloadRequest.decode("")).isNull()
        assertThat(DownloadRequest.decode("kaputt")).isNull()
    }
}
