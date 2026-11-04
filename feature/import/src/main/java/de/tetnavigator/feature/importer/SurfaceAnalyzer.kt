package de.tetnavigator.feature.importer

import de.tetnavigator.core.common.BoundingBox
import de.tetnavigator.core.common.Geo
import de.tetnavigator.core.common.GeoPoint
import de.tetnavigator.core.database.TetDatabase
import de.tetnavigator.core.database.TrackPointEntity
import de.tetnavigator.core.offlinedata.PoiDatabase
import de.tetnavigator.core.offlinedata.SurfaceClass
import de.tetnavigator.core.offlinedata.SurfaceWay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Untergrund-Färbung: ordnet jedem Track-Segment den Belag des nächstgelegenen
 * OSM-Wegs (surface/tracktype) aus dem Offline-POI-Paket zu.
 */
@Singleton
class SurfaceAnalyzer @Inject constructor(
    private val db: TetDatabase,
    private val poi: PoiDatabase,
) {
    data class Stats(val gravelM: Double, val dirtM: Double, val asphaltM: Double, val unknownM: Double) {
        val total get() = gravelM + dirtM + asphaltM + unknownM
    }

    suspend fun analyzePending(): Int = withContext(Dispatchers.IO) {
        if (!poi.hasSurfaceData()) return@withContext 0
        val pending = db.trackDao().sectionsWithoutSurface()
        pending.forEach { analyze(it.id) }
        pending.size
    }

    suspend fun analyze(sectionId: Long): Stats? = withContext(Dispatchers.IO) {
        if (!poi.hasSurfaceData()) return@withContext null
        val dao = db.trackDao()
        val pts = dao.points(sectionId)
        if (pts.size < 2) return@withContext null
        val classes = classify(pts) { box ->
            poi.surfaceWaysNear(box.centerLat, box.centerLon, Geo.distance(box.minLat, box.minLon, box.maxLat, box.maxLon) / 2 + MATCH_M)
        }
        // in Läufen gleicher Klasse schreiben
        var start = 0
        for (i in 1..classes.size) {
            if (i == classes.size || classes[i] != classes[start]) {
                dao.updateSurface(sectionId, start, i - 1, classes[start].code)
                start = i
            }
        }
        val stats = stats(pts, classes)
        dao.updateSurfaceStats(sectionId, stats.gravelM, stats.dirtM, stats.asphaltM, stats.unknownM)
        stats
    }

    companion object {
        const val MATCH_M = 25.0
        private const val CHUNK = 40

        /** Klasse je Segment (idx → idx+1); letzte Position erbt vom Vorgänger. */
        suspend fun classify(pts: List<TrackPointEntity>, waysIn: suspend (BoundingBox) -> List<SurfaceWay>): List<SurfaceClass> {
            val out = ArrayList<SurfaceClass>(pts.size)
            var i = 0
            while (i < pts.size - 1) {
                val end = (i + CHUNK).coerceAtMost(pts.size - 1)
                val chunk = pts.subList(i, end + 1)
                val box = BoundingBox.of(chunk.map { GeoPoint(it.lat, it.lon) })!!
                val ways = waysIn(box)
                for (k in i until end) {
                    val a = pts[k]; val b = pts[k + 1]
                    out += classifySegment((a.lat + b.lat) / 2, (a.lon + b.lon) / 2, ways)
                }
                i = end
            }
            out += out.lastOrNull() ?: SurfaceClass.UNKNOWN
            return out
        }

        fun classifySegment(lat: Double, lon: Double, ways: List<SurfaceWay>): SurfaceClass {
            var best: SurfaceWay? = null
            var bestD = MATCH_M
            for (w in ways) for (j in 0 until w.coords.size - 1) {
                val a = w.coords[j]; val b = w.coords[j + 1]
                val d = Geo.project(lat, lon, a.lat, a.lon, b.lat, b.lon).distanceM
                if (d < bestD) { bestD = d; best = w }
            }
            return best?.let { SurfaceClass.classify(it.surface, it.tracktype, it.highway) } ?: SurfaceClass.UNKNOWN
        }

        fun stats(pts: List<TrackPointEntity>, classes: List<SurfaceClass>): Stats {
            var g = 0.0; var d = 0.0; var a = 0.0; var u = 0.0
            for (k in 0 until pts.size - 1) {
                val len = pts[k + 1].distM - pts[k].distM
                when (classes[k]) {
                    SurfaceClass.GRAVEL -> g += len
                    SurfaceClass.DIRT -> d += len
                    SurfaceClass.ASPHALT -> a += len
                    SurfaceClass.UNKNOWN -> u += len
                }
            }
            return Stats(g, d, a, u)
        }
    }
}
