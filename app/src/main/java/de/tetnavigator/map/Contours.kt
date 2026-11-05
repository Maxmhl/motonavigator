package de.tetnavigator.map

import de.tetnavigator.core.offlinedata.TileMath
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sinh

/** Eine Höhenlinie als Folge (lon, lat). */
class ContourLine(val elevation: Int, val lon: DoubleArray, val lat: DoubleArray)

/**
 * Höhenlinien aus einem Höhenraster (Marching Squares), komplett offline.
 * Das Raster deckt die Kachel z/x/y ab (Web-Mercator, [size]×[size] Werte).
 */
object Contours {
    /** Terrarium-Kodierung: (R·256 + G + B/256) − 32768 Meter. */
    fun terrariumElevation(argb: Int): Float {
        val r = (argb shr 16) and 0xff; val g = (argb shr 8) and 0xff; val b = argb and 0xff
        return (r * 256f + g + b / 256f) - 32768f
    }

    fun generate(
        elev: FloatArray, size: Int, z: Int, tx: Int, ty: Int,
        interval: Int, step: Int = 2,
    ): List<ContourLine> {
        // Raster ausdünnen (step) – reicht für die Darstellung und spart Rechenzeit
        val n = (size - 1) / step + 1
        fun e(i: Int, j: Int) = elev[(j * step).coerceAtMost(size - 1) * size + (i * step).coerceAtMost(size - 1)]
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (v in elev) { if (v < lo) lo = v; if (v > hi) hi = v }
        if (hi - lo < interval) return emptyList()
        val worldSize = (1 shl z).toDouble()
        fun lonOf(px: Double) = (tx + px / (size - 1)) / worldSize * 360.0 - 180.0
        fun latOf(py: Double) = Math.toDegrees(atan(sinh(PI * (1 - 2 * (ty + py / (size - 1)) / worldSize))))

        val out = ArrayList<ContourLine>()
        var level = ceil(lo / interval).toInt() * interval
        while (level <= floor(hi)) {
            val segs = ArrayList<DoubleArray>()
            val lv = level.toFloat()
            for (j in 0 until n - 1) for (i in 0 until n - 1) {
                val a = e(i, j); val b = e(i + 1, j); val c = e(i + 1, j + 1); val d = e(i, j + 1)
                var idx = 0
                if (a >= lv) idx = idx or 8
                if (b >= lv) idx = idx or 4
                if (c >= lv) idx = idx or 2
                if (d >= lv) idx = idx or 1
                if (idx == 0 || idx == 15) continue
                // Kantenpunkte (in Rasterpixeln)
                fun top() = doubleArrayOf((i + t(a, b, lv)) * step, (j * step).toDouble())
                fun right() = doubleArrayOf(((i + 1) * step).toDouble(), (j + t(b, c, lv)) * step)
                fun bottom() = doubleArrayOf((i + t(d, c, lv)) * step, ((j + 1) * step).toDouble())
                fun left() = doubleArrayOf((i * step).toDouble(), (j + t(a, d, lv)) * step)
                fun seg(p: DoubleArray, q: DoubleArray) { segs += doubleArrayOf(p[0], p[1], q[0], q[1]) }
                when (idx) {
                    1, 14 -> seg(left(), bottom())
                    2, 13 -> seg(bottom(), right())
                    3, 12 -> seg(left(), right())
                    4, 11 -> seg(top(), right())
                    5 -> { seg(left(), top()); seg(bottom(), right()) }
                    6, 9 -> seg(top(), bottom())
                    7, 8 -> seg(left(), top())
                    10 -> { seg(left(), bottom()); seg(top(), right()) }
                }
            }
            for (line in join(segs)) {
                val lons = DoubleArray(line.size / 2) { lonOf(line[it * 2]) }
                val lats = DoubleArray(line.size / 2) { latOf(line[it * 2 + 1]) }
                if (lons.size >= 2) out += ContourLine(level, lons, lats)
            }
            level += interval
        }
        return out
    }

    private fun t(a: Float, b: Float, lv: Float): Double = if (a == b) 0.5 else ((lv - a) / (b - a)).toDouble().coerceIn(0.0, 1.0)

    /** Segmente an gemeinsamen Endpunkten zu Linien verbinden. */
    private fun join(segs: List<DoubleArray>): List<DoubleArray> {
        fun key(x: Double, y: Double) = (Math.round(x * 1000) shl 32) xor (Math.round(y * 1000) and 0xffffffffL)
        val byEnd = HashMap<Long, MutableList<Int>>()
        segs.forEachIndexed { i, s ->
            byEnd.getOrPut(key(s[0], s[1])) { ArrayList(2) } += i
            byEnd.getOrPut(key(s[2], s[3])) { ArrayList(2) } += i
        }
        val used = BooleanArray(segs.size)
        val lines = ArrayList<DoubleArray>()
        for (start in segs.indices) {
            if (used[start]) continue
            used[start] = true
            val pts = ArrayDeque<Double>()
            val s0 = segs[start]
            pts.addLast(s0[0]); pts.addLast(s0[1]); pts.addLast(s0[2]); pts.addLast(s0[3])
            // nach hinten verlängern
            fun extend(forward: Boolean) {
                while (true) {
                    val x = if (forward) pts[pts.size - 2] else pts[0]
                    val y = if (forward) pts[pts.size - 1] else pts[1]
                    val next = byEnd[key(x, y)]?.firstOrNull { !used[it] } ?: return
                    used[next] = true
                    val s = segs[next]
                    val sameStart = key(s[0], s[1]) == key(x, y)
                    val nx = if (sameStart) s[2] else s[0]; val ny = if (sameStart) s[3] else s[1]
                    if (forward) { pts.addLast(nx); pts.addLast(ny) } else { pts.addFirst(ny); pts.addFirst(nx) }
                }
            }
            extend(true); extend(false)
            lines += pts.toDoubleArray()
        }
        return lines
    }

    /** Intervall nach Zoom: nah 20 m, mittel 50 m, weit 100 m. */
    fun intervalFor(zoom: Double): Int = when {
        zoom >= 14 -> 20
        zoom >= 13 -> 50
        else -> 100
    }

    /** Höhenkacheln (z = [demZoom]), die einen Kartenausschnitt abdecken. */
    fun tilesFor(minLon: Double, minLat: Double, maxLon: Double, maxLat: Double, demZoom: Int): List<Pair<Int, Int>> {
        val x0 = TileMath.lonToX(minLon, demZoom); val x1 = TileMath.lonToX(maxLon, demZoom)
        val y0 = TileMath.latToY(maxLat, demZoom); val y1 = TileMath.latToY(minLat, demZoom)
        return (x0..x1).flatMap { x -> (y0..y1).map { y -> x to y } }
    }
}
