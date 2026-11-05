package de.tetnavigator.feature.navigation

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import de.tetnavigator.core.common.Geo
import de.tetnavigator.core.common.LatLon
import de.tetnavigator.core.offlinedata.OfflineStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class Route(val points: List<LatLon>, val distanceM: Double, val ascentM: Double, val seconds: Int)

/** Kreisförmige Sperrfläche für Umfahrungen. */
data class NoGo(val lat: Double, val lon: Double, val radiusM: Double)

class RoutingUnavailableException(msg: String) : Exception(msg)

/**
 * Offline-Routing mit BRouter-Segmenten (.rd5) aus dem Einrichtungs-Download
 * und dem eigenen Schotter-Profil `tet-schotter.brf`. Kein Netzwerk.
 */
@Singleton
class OfflineRouter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storage: OfflineStorage,
) {
    val available: Boolean get() = storage.hasRouting()

    /** Profil-Dateien aus den Assets ins App-Verzeichnis kopieren (BRouter braucht echte Dateien). */
    fun profileFile(name: String = PROFILE): File {
        val dir = storage.profilesDir
        for (n in listOf("lookups.dat", name)) {
            val f = File(dir, n)
            if (!f.exists()) context.assets.open("brouter/$n").use { i -> f.outputStream().use { i.copyTo(it) } }
        }
        return File(dir, name)
    }

    suspend fun route(from: LatLon, to: LatLon, nogos: List<NoGo> = emptyList(), timeoutMs: Long = 60_000): Route =
        withContext(Dispatchers.Default) {
            if (!available) throw RoutingUnavailableException("Keine Routing-Segmente installiert (Einrichtung → Pakete laden)")
            BRouterEngine.route(storage.segmentsDir, profileFile(), listOf(from, to), nogos, timeoutMs).first
        }

    /** Route entlang vorgegebener Wegpunkte mit OSM-Tags je Punkt (für die Untergrund-Analyse). */
    suspend fun matchWithTags(waypoints: List<LatLon>, timeoutMs: Long = 120_000): List<RoutePoint> =
        withContext(Dispatchers.Default) {
            if (!available) throw RoutingUnavailableException("Keine Routing-Segmente installiert")
            BRouterEngine.route(storage.segmentsDir, profileFile(MATCH_PROFILE), waypoints, emptyList(), timeoutMs).second
        }

    /**
     * Umfahrung: Sperrkreise entlang des gesperrten Abschnitts, Route vom Punkt
     * vor der Sperrung zum Punkt danach.
     */
    suspend fun detour(track: ActiveTrack, blockStartM: Double, blockEndM: Double): Route {
        val from = track.pointAt((blockStartM - 150).coerceAtLeast(0.0))
        val to = track.pointAt((blockEndM + 150).coerceAtMost(track.lengthM))
        val nogos = ArrayList<NoGo>()
        var d = blockStartM
        while (d <= blockEndM) {
            val p = track.pointAt(d)
            nogos += NoGo(p.lat, p.lon, 40.0)
            d += 60.0
        }
        return route(LatLon(from.lat, from.lon), LatLon(to.lat, to.lon), nogos)
    }

    companion object {
        const val PROFILE = "tet-schotter.brf"
        const val MATCH_PROFILE = "tet-match.brf"

        fun lengthOf(points: List<LatLon>): Double {
            var s = 0.0
            for (i in 1 until points.size) s += Geo.distance(points[i - 1], points[i])
            return s
        }
    }
}
