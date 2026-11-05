package de.tetnavigator.core.offlinedata

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/** Web-Mercator-Kachelmathematik und PMTiles-Hilbert-IDs. */
object TileMath {
    fun lonToX(lon: Double, z: Int): Int = floor((lon + 180.0) / 360.0 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)

    fun latToY(lat: Double, z: Int): Int {
        val l = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
        return floor((1.0 - ln(tan(l) + 1.0 / kotlin.math.cos(l)) / PI) / 2.0 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)
    }

    fun xToLon(x: Int, z: Int): Double = x.toDouble() / (1 shl z) * 360.0 - 180.0
    fun yToLat(y: Int, z: Int): Double = Math.toDegrees(atan(sinh(PI * (1 - 2.0 * y / (1 shl z)))))

    /** (minLon, minLat, maxLon, maxLat) einer Kachel. */
    fun bounds(z: Int, x: Int, y: Int) = doubleArrayOf(xToLon(x, z), yToLat(y + 1, z), xToLon(x + 1, z), yToLat(y, z))

    private val zoomBase = LongArray(32).also { a -> for (z in 1 until 32) a[z] = a[z - 1] + (1L shl (2 * (z - 1))) }

    /** Hilbert-Kurven-ID laut PMTiles-v3-Spezifikation. */
    fun zxyToTileId(z: Int, x: Int, y: Int): Long {
        require(z in 0..26)
        var tx = x.toLong(); var ty = y.toLong()
        var d = 0L
        var s = (1L shl z) / 2
        while (s > 0) {
            val rx = if (tx and s > 0) 1L else 0L
            val ry = if (ty and s > 0) 1L else 0L
            d += s * s * ((3L * rx) xor ry)
            // rotieren
            if (ry == 0L) {
                if (rx == 1L) { tx = s - 1 - tx; ty = s - 1 - ty }
                val t = tx; tx = ty; ty = t
            }
            s /= 2
        }
        return zoomBase[z] + d
    }

    fun zoomOf(tileId: Long): Int {
        var z = 0
        while (z < 31 && zoomBase[z + 1] <= tileId) z++
        return z
    }
}
