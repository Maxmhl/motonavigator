package de.tetnavigator.feature.navigation

import de.tetnavigator.core.common.Geo
import de.tetnavigator.core.common.GeoPoint
import kotlin.math.cos
import kotlin.math.floor

/** Ein Punkt der aktiven Sektion in Fahrtrichtung. */
data class TrackVertex(
    val lat: Double,
    val lon: Double,
    val ele: Double?,
    /** Distanz ab Start (in Fahrtrichtung). */
    val distM: Double,
    /** Untergrund des Segments vertex → vertex+1. */
    val surface: Int,
    val changed: Boolean,
)

/**
 * Aktive Sektion in Fahrtrichtung (ggf. umgekehrt) mit In-Memory-Raster der
 * Segmente für das Map-Matching im Millisekundenbereich.
 */
class ActiveTrack(
    val sectionId: Long,
    val name: String,
    val countryCode: String,
    val reversed: Boolean,
    val vertices: List<TrackVertex>,
) {
    val lengthM: Double = vertices.lastOrNull()?.distM ?: 0.0
    val turns: List<TurnEvent> by lazy { CurveDetector().detect(vertices) }

    private val lat0 = vertices.firstOrNull()?.lat ?: 0.0
    private val mPerDegLon = Geo.METERS_PER_DEG_LAT * cos(Math.toRadians(lat0)).coerceAtLeast(0.01)
    private val cells = HashMap<Long, MutableList<Int>>()

    init {
        for (i in 0 until vertices.size - 1) {
            val a = vertices[i]; val b = vertices[i + 1]
            for (x in cx(minOf(a.lon, b.lon))..cx(maxOf(a.lon, b.lon)))
                for (y in cy(minOf(a.lat, b.lat))..cy(maxOf(a.lat, b.lat)))
                    cells.getOrPut(key(x, y)) { ArrayList(4) } += i
        }
    }

    private fun cx(lon: Double) = floor(lon * mPerDegLon / CELL_M).toInt()
    private fun cy(lat: Double) = floor(lat * Geo.METERS_PER_DEG_LAT / CELL_M).toInt()
    private fun key(x: Int, y: Int) = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)

    /** Segment-Indizes im Umkreis [radiusM]. */
    fun segmentsNear(lat: Double, lon: Double, radiusM: Double): Set<Int> {
        val r = (radiusM / CELL_M).toInt() + 1
        val x = cx(lon); val y = cy(lat)
        val out = HashSet<Int>()
        for (dx in -r..r) for (dy in -r..r) cells[key(x + dx, y + dy)]?.let(out::addAll)
        return out
    }

    fun segmentBearing(i: Int): Double {
        val a = vertices[i]; val b = vertices[(i + 1).coerceAtMost(vertices.size - 1)]
        return Geo.bearing(a.lat, a.lon, b.lat, b.lon)
    }

    /** Segment-Index zur Distanz (binäre Suche). */
    fun indexAt(distM: Double): Int {
        var lo = 0; var hi = vertices.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (vertices[mid].distM <= distM) lo = mid else hi = mid - 1
        }
        return lo.coerceAtMost(vertices.size - 2).coerceAtLeast(0)
    }

    fun pointAt(distM: Double): GeoPoint {
        val i = indexAt(distM)
        val a = vertices[i]; val b = vertices[(i + 1).coerceAtMost(vertices.size - 1)]
        val f = if (b.distM > a.distM) ((distM - a.distM) / (b.distM - a.distM)).coerceIn(0.0, 1.0) else 0.0
        return GeoPoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f, a.ele)
    }

    /** Erster Untergrundwechsel nach [fromM] (Distanz, neuer Code). */
    fun nextSurfaceChange(fromM: Double, withinM: Double): Pair<Double, Int>? {
        var i = indexAt(fromM)
        val start = vertices[i].surface
        while (i < vertices.size - 1 && vertices[i].distM <= fromM + withinM) {
            if (vertices[i].surface != start) return vertices[i].distM to vertices[i].surface
            i++
        }
        return null
    }

    companion object {
        const val CELL_M = 100.0

        /** Baut die Fahrtrichtung; bei [reversed] wird die Liste umgedreht und neu vermessen. */
        fun build(sectionId: Long, name: String, cc: String, reversed: Boolean, pts: List<TrackVertex>): ActiveTrack {
            if (!reversed) return ActiveTrack(sectionId, name, cc, false, pts)
            val total = pts.lastOrNull()?.distM ?: 0.0
            val rev = pts.asReversed().mapIndexed { i, v ->
                // Untergrund gehört zum Segment v → v+1; umgekehrt also vom Vorgänger.
                val surf = if (i + 1 < pts.size) pts[pts.size - 2 - i].surface else v.surface
                v.copy(distM = total - v.distM, surface = surf)
            }
            return ActiveTrack(sectionId, name, cc, true, rev)
        }
    }
}
