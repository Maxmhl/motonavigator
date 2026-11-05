package de.tetnavigator.feature.navigation

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.tetnavigator.core.common.Geo
import de.tetnavigator.core.common.LatLon
import de.tetnavigator.core.offlinedata.SurfaceClass
import de.tetnavigator.core.offlinedata.SurfaceTagSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.floor

/**
 * Untergrund aus den BRouter-Segmenten: Route entlang des Tracks (Wegpunkte
 * alle ~400 m, Match-Profil) und Belag-Tags der getroffenen OSM-Wege übernehmen.
 * Braucht keine zusätzlichen Daten – nur die ohnehin geladenen .rd5-Dateien.
 */
@Singleton
class BRouterSurfaceSource @Inject constructor(private val router: OfflineRouter) : SurfaceTagSource {
    override val available: Boolean get() = router.available

    override suspend fun classify(points: List<LatLon>): List<SurfaceClass> {
        if (points.size < 2) return points.map { SurfaceClass.UNKNOWN }
        val routePts = ArrayList<RoutePoint>()
        // Wegpunkte ausdünnen, in Blöcken routen (BRouter mag keine hunderte Via-Punkte)
        val wps = thin(points, 400.0)
        for (chunk in wps.windowed(25, 24, partialWindows = true)) {
            if (chunk.size < 2) continue
            routePts += runCatching { router.matchWithTags(chunk) }.getOrDefault(emptyList())
        }
        if (routePts.isEmpty()) return points.map { SurfaceClass.UNKNOWN }
        val grid = PointGrid(routePts)
        return points.map { p ->
            val hit = grid.nearest(p.lat, p.lon, 40.0) ?: return@map SurfaceClass.UNKNOWN
            SurfaceClass.classify(hit.wayTags["surface"], hit.wayTags["tracktype"], hit.wayTags["highway"])
        }
    }

    private fun thin(pts: List<LatLon>, stepM: Double): List<LatLon> {
        val out = arrayListOf(pts.first())
        var acc = 0.0
        for (i in 1 until pts.size) {
            acc += Geo.distance(pts[i - 1], pts[i])
            if (acc >= stepM) { out += pts[i]; acc = 0.0 }
        }
        if (out.last() != pts.last()) out += pts.last()
        return out
    }

    /** Raster über Routenpunkte für die Suche „nächster getaggter Punkt“. */
    private class PointGrid(private val pts: List<RoutePoint>) {
        private val lat0 = pts.first().lat
        private val mLon = Geo.METERS_PER_DEG_LAT * cos(Math.toRadians(lat0)).coerceAtLeast(0.05)
        private val cells = HashMap<Long, MutableList<Int>>()
        private fun cx(lon: Double) = floor(lon * mLon / CELL).toInt()
        private fun cy(lat: Double) = floor(lat * Geo.METERS_PER_DEG_LAT / CELL).toInt()
        private fun key(x: Int, y: Int) = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)

        init { pts.forEachIndexed { i, p -> if (p.wayTags.isNotEmpty()) cells.getOrPut(key(cx(p.lon), cy(p.lat))) { ArrayList(4) } += i } }

        fun nearest(lat: Double, lon: Double, maxM: Double): RoutePoint? {
            val x = cx(lon); val y = cy(lat)
            var best: RoutePoint? = null; var bestD = maxM
            for (dx in -1..1) for (dy in -1..1) for (i in cells[key(x + dx, y + dy)].orEmpty()) {
                val d = Geo.distance(lat, lon, pts[i].lat, pts[i].lon)
                if (d < bestD) { bestD = d; best = pts[i] }
            }
            return best
        }

        companion object { const val CELL = 50.0 }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SurfaceSourceModule {
    @Binds abstract fun surfaceSource(impl: BRouterSurfaceSource): SurfaceTagSource
}
