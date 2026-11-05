package de.tetnavigator.feature.navigation

import btools.router.OsmNodeNamed
import btools.router.OsmPathElement
import btools.router.RoutingContext
import btools.router.RoutingEngine
import de.tetnavigator.core.common.LatLon
import java.io.File

/** Ein Routenpunkt mit den OSM-Tags des Wegs, auf dem er liegt (z. B. "highway=track surface=gravel"). */
data class RoutePoint(val lat: Double, val lon: Double, val wayTags: Map<String, String>)

/**
 * Dünne, Android-freie Hülle um BRouter (läuft auch in JVM-Tests).
 * Liest ausschließlich lokale .rd5-Segmente.
 */
object BRouterEngine {

    fun route(
        segmentsDir: File,
        profile: File,
        waypoints: List<LatLon>,
        nogos: List<NoGo> = emptyList(),
        timeoutMs: Long = 60_000,
    ): Pair<Route, List<RoutePoint>> {
        require(waypoints.size >= 2) { "Mindestens zwei Wegpunkte" }
        val rc = RoutingContext()
        rc.localFunction = profile.absolutePath
        rc.nogopoints = ArrayList<OsmNodeNamed>().apply {
            nogos.forEachIndexed { i, n -> add(node(n.lat, n.lon, "nogo${n.radiusM.toInt()} $i").apply { isNogo = true; nogoWeight = Double.NaN }) }
        }
        RoutingContext.prepareNogoPoints(rc.nogopoints)
        val wps = waypoints.mapIndexed { i, p -> node(p.lat, p.lon, if (i == 0) "from" else if (i == waypoints.lastIndex) "to" else "via$i") }
        val engine = RoutingEngine(null, null, segmentsDir, wps, rc)
        engine.quite = true
        engine.doRun(timeoutMs)
        engine.errorMessage?.let { throw RoutingUnavailableException("Keine Route gefunden: $it") }
        val track = engine.foundTrack ?: throw RoutingUnavailableException("Keine Route gefunden")
        val points = track.nodes.map { RoutePoint(it.iLat / 1e6 - 90.0, it.iLon / 1e6 - 180.0, wayTags(it)) }
        return Route(points.map { LatLon(it.lat, it.lon) }, track.distance.toDouble(), track.ascend.toDouble(), track.totalSeconds) to points
    }

    private val wayKeyValuesField by lazy {
        runCatching { Class.forName("btools.router.MessageData").getDeclaredField("wayKeyValues").apply { isAccessible = true } }.getOrNull()
    }

    /** MessageData.wayKeyValues ist paketintern – per Reflection lesen. */
    private fun wayTags(e: OsmPathElement): Map<String, String> {
        val msg = e.message ?: return emptyMap()
        val s = runCatching { wayKeyValuesField?.get(msg) as? String }.getOrNull() ?: return emptyMap()
        return s.split(' ').mapNotNull { kv -> kv.indexOf('=').takeIf { it > 0 }?.let { kv.substring(0, it) to kv.substring(it + 1) } }.toMap()
    }

    private fun node(lat: Double, lon: Double, name: String) = OsmNodeNamed().apply {
        this.name = name
        ilon = ((lon + 180.0) * 1e6 + 0.5).toInt()
        ilat = ((lat + 90.0) * 1e6 + 0.5).toInt()
    }
}
