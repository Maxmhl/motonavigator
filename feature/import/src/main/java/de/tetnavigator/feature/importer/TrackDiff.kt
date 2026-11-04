package de.tetnavigator.feature.importer

import de.tetnavigator.core.common.Geo
import de.tetnavigator.core.common.GeoPoint
import kotlin.math.cos
import kotlin.math.floor

/**
 * Update-Vergleich: legt eine neue Track-Version gegen die alte. Neue Punkte,
 * die weiter als [toleranceM] von der alten Linie entfernt sind, gelten als
 * geändert; zusammenhängende Läufe ≥ [minRunM] ergeben „geänderte Abschnitte“.
 */
class TrackDiff(private val toleranceM: Double = 25.0, private val minRunM: Double = 50.0) {

    data class Result(
        /** Je neuem Punkt: liegt in geändertem Abschnitt. */
        val changed: BooleanArray,
        /** Geänderte Bereiche als (startM, endM) entlang der neuen Linie. */
        val ranges: List<Pair<Double, Double>>,
    ) {
        val changedCount: Int get() = ranges.size
    }

    fun compare(old: List<GeoPoint>, new: List<GeoPoint>): Result {
        val n = new.size
        val changed = BooleanArray(n)
        if (old.size < 2 || n < 2) return Result(BooleanArray(n) { true }, if (n >= 2) listOf(0.0 to Geo.polylineLength(new)) else emptyList())
        val index = SegmentGrid(old, cellM = 200.0)
        val raw = BooleanArray(n) { index.distanceTo(new[it]) > toleranceM }
        val cum = Geo.cumulative(new)

        val ranges = ArrayList<Pair<Double, Double>>()
        var i = 0
        while (i < n) {
            if (!raw[i]) { i++; continue }
            var j = i
            while (j + 1 < n && raw[j + 1]) j++
            val startM = cum[(i - 1).coerceAtLeast(0)]
            val endM = cum[(j + 1).coerceAtMost(n - 1)]
            if (endM - startM >= minRunM) {
                ranges += startM to endM
                for (k in (i - 1).coerceAtLeast(0)..(j + 1).coerceAtMost(n - 1)) changed[k] = true
            }
            i = j + 1
        }
        return Result(changed, ranges)
    }

    companion object {
        fun encodeRanges(r: List<Pair<Double, Double>>) = r.joinToString(";") { "${it.first.toLong()}-${it.second.toLong()}" }
        fun decodeRanges(s: String): List<Pair<Double, Double>> = s.split(';').mapNotNull {
            val parts = it.split('-')
            if (parts.size != 2) null else {
                val a = parts[0].toDoubleOrNull(); val b = parts[1].toDoubleOrNull()
                if (a != null && b != null) a to b else null
            }
        }
    }
}

/**
 * Einfaches Raster über die Segmente einer Linie für schnelle
 * Abstandsabfragen (Diff, Fortschritts-Übertragung).
 */
class SegmentGrid(private val pts: List<GeoPoint>, private val cellM: Double = 200.0) {
    private val lat0 = pts.firstOrNull()?.lat ?: 0.0
    private val mPerDegLon = Geo.METERS_PER_DEG_LAT * cos(Math.toRadians(lat0)).coerceAtLeast(0.01)
    private val cells = HashMap<Long, MutableList<Int>>()

    init {
        for (i in 0 until pts.size - 1) {
            val a = pts[i]; val b = pts[i + 1]
            val x0 = cx(minOf(a.lon, b.lon)); val x1 = cx(maxOf(a.lon, b.lon))
            val y0 = cy(minOf(a.lat, b.lat)); val y1 = cy(maxOf(a.lat, b.lat))
            for (x in x0..x1) for (y in y0..y1) cells.getOrPut(key(x, y)) { ArrayList(4) } += i
        }
    }

    private fun cx(lon: Double) = floor(lon * mPerDegLon / cellM).toInt()
    private fun cy(lat: Double) = floor(lat * Geo.METERS_PER_DEG_LAT / cellM).toInt()
    private fun key(x: Int, y: Int) = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)

    /** Index des nächsten Segments und Projektion, gesucht in 3×3 Zellen. */
    fun nearest(p: GeoPoint, rings: Int = 1): Pair<Int, de.tetnavigator.core.common.SegmentProjection>? {
        val x = cx(p.lon); val y = cy(p.lat)
        var best: Pair<Int, de.tetnavigator.core.common.SegmentProjection>? = null
        val seen = HashSet<Int>()
        for (dx in -rings..rings) for (dy in -rings..rings) {
            for (i in cells[key(x + dx, y + dy)].orEmpty()) {
                if (!seen.add(i)) continue
                val a = pts[i]; val b = pts[i + 1]
                val pr = Geo.project(p.lat, p.lon, a.lat, a.lon, b.lat, b.lon)
                if (best == null || pr.distanceM < best.second.distanceM) best = i to pr
            }
        }
        return best
    }

    fun distanceTo(p: GeoPoint): Double = nearest(p)?.second?.distanceM ?: Double.MAX_VALUE
}
