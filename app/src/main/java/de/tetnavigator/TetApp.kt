package de.tetnavigator

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import de.tetnavigator.feature.importer.SurfaceAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import javax.inject.Inject

@HiltAndroidApp
class TetApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var surfaceAnalyzer: SurfaceAnalyzer

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        // Harte Regel 1: MapLibre darf nie selbst ins Netz – Karten kommen nur aus lokalen PMTiles.
        MapLibre.setConnected(false)
        // Untergrund für Sektionen ohne Analyse nachholen (rein lokal).
        appScope.launch { runCatching { surfaceAnalyzer.analyzePending() } }
    }
}
