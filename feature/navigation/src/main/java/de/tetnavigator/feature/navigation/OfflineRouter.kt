package de.tetnavigator.feature.navigation

import android.content.Context
import btools.router.OsmNodeNamed
import btools.router.RoutingContext
import btools.router.RoutingEngine
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

    private fun profile(): File {
        val dir = storage.profilesDir
        for (name in listOf("lookups.dat", PROFILE)) {
            val f = File(dir, name)
            if (!f.exists()) context.assets.open("brouter/$name").use { i -> f.outputStream().use { i.copyTo(it) } }
        }
        return File(dir, PROFILE)
    }

    suspend fun route(from: LatLon, to: LatLon, nogos: List<NoGo> = emptyList(), timeoutMs: Long = 60_000): Route =
        withContext(Dispatchers.Default) {
            if (!available) throw RoutingUnavailableException("Keine Routing-Segmente installiert (Einrichtung → Pakete laden)")
            val rc = RoutingContext()
            rc.localFunction = profile().absolutePath
            rc.nogopoints = ArrayList<OsmNodeNamed>().apply {
                nogos.forEachIndexed { i, n ->
                    add(node(n.lat, n.lon, "nogo${n.radiusM.toInt()} $i").apply { isNogo = true; nogoWeight = Double.NaN })
                }
            }
            RoutingContext.prepareNogoPoints(rc.nogopoints)
            val wps = listOf(node(from.lat, from.lon, "from"), node(to.lat, to.lon, "to"))
            val engine = RoutingEngine(null, null, storage.segmentsDir, wps, rc)
            engine.quite = true
            engine.doRun(timeoutMs)
            engine.errorMessage?.let { throw RoutingUnavailableException("Keine Route gefunden: $it") }
            val track = engine.foundTrack ?: throw RoutingUnavailableException("Keine Route gefunden")
            val pts = track.nodes.map { LatLon(it.iLat / 1e6 - 90.0, it.iLon / 1e6 - 180.0) }
            Route(pts, track.distance.toDouble(), track.ascend.toDouble(), track.totalSeconds)
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

        private fun node(lat: Double, lon: Double, name: String) = OsmNodeNamed().apply {
            this.name = name
            ilon = ((lon + 180.0) * 1e6 + 0.5).toInt()
            ilat = ((lat + 90.0) * 1e6 + 0.5).toInt()
        }

        fun lengthOf(points: List<LatLon>): Double {
            var s = 0.0
            for (i in 1 until points.size) s += Geo.distance(points[i - 1], points[i])
            return s
        }
    }
}
