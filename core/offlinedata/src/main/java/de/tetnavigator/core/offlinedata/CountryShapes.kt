package de.tetnavigator.core.offlinedata

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import de.tetnavigator.core.common.Geo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** Ein Ring als parallele Arrays (lon, lat). */
class Ring(val lon: DoubleArray, val lat: DoubleArray) {
    val minLon = lon.min(); val maxLon = lon.max(); val minLat = lat.min(); val maxLat = lat.max()

    fun contains(x: Double, y: Double): Boolean {
        if (x < minLon || x > maxLon || y < minLat || y > maxLat) return false
        var inside = false
        var j = lon.size - 1
        for (i in lon.indices) {
            if ((lat[i] > y) != (lat[j] > y) && x < (lon[j] - lon[i]) * (y - lat[i]) / (lat[j] - lat[i]) + lon[i]) inside = !inside
            j = i
        }
        return inside
    }
}

/** Landesfläche (Außenringe, Natural Earth 1:50m) für Download-Masken. */
class CountryShape(val code: String, val rings: List<Ring>) {
    fun contains(lon: Double, lat: Double) = rings.any { it.contains(lon, lat) }

    /** Schneidet das Rechteck (mit Rand in m) die Landesfläche? */
    fun intersects(minLon: Double, minLat: Double, maxLon: Double, maxLat: Double, marginM: Double = 0.0): Boolean {
        val dLat = marginM / Geo.METERS_PER_DEG_LAT
        val dLon = marginM / (Geo.METERS_PER_DEG_LAT * cos(Math.toRadians((minLat + maxLat) / 2)).coerceAtLeast(0.05))
        val x0 = minLon - dLon; val x1 = maxLon + dLon; val y0 = minLat - dLat; val y1 = maxLat + dLat
        for (r in rings) {
            if (r.maxLon < x0 || r.minLon > x1 || r.maxLat < y0 || r.minLat > y1) continue
            if (r.contains((x0 + x1) / 2, (y0 + y1) / 2)) return true
            val n = r.lon.size
            for (i in 0 until n) {
                val ax = r.lon[i]; val ay = r.lat[i]
                if (ax in x0..x1 && ay in y0..y1) return true
                val bx = r.lon[(i + 1) % n]; val by = r.lat[(i + 1) % n]
                if (max(ax, bx) < x0 || min(ax, bx) > x1 || max(ay, by) < y0 || min(ay, by) > y1) continue
                if (segIntersects(ax, ay, bx, by, x0, y0, x1, y0) || segIntersects(ax, ay, bx, by, x1, y0, x1, y1) ||
                    segIntersects(ax, ay, bx, by, x1, y1, x0, y1) || segIntersects(ax, ay, bx, by, x0, y1, x0, y0)
                ) return true
            }
        }
        return false
    }

    /** Liegt das Rechteck vollständig im Land (alle Ecken innen, keine Kante kreuzt)? */
    fun containsRect(minLon: Double, minLat: Double, maxLon: Double, maxLat: Double): Boolean {
        if (!(contains(minLon, minLat) && contains(maxLon, minLat) && contains(maxLon, maxLat) && contains(minLon, maxLat))) return false
        for (r in rings) {
            if (r.maxLon < minLon || r.minLon > maxLon || r.maxLat < minLat || r.minLat > maxLat) continue
            val n = r.lon.size
            for (i in 0 until n) {
                val ax = r.lon[i]; val ay = r.lat[i]
                if (ax > minLon && ax < maxLon && ay > minLat && ay < maxLat) return false
            }
        }
        return true
    }

    private fun segIntersects(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, dx: Double, dy: Double): Boolean {
        fun cross(ox: Double, oy: Double, px: Double, py: Double, qx: Double, qy: Double) = (px - ox) * (qy - oy) - (py - oy) * (qx - ox)
        val d1 = cross(cx, cy, dx, dy, ax, ay); val d2 = cross(cx, cy, dx, dy, bx, by)
        val d3 = cross(ax, ay, bx, by, cx, cy); val d4 = cross(ax, ay, bx, by, dx, dy)
        return ((d1 > 0) != (d2 > 0)) && ((d3 > 0) != (d4 > 0))
    }

    /**
     * Alle Kachel-IDs (PMTiles-Hilbert) von Zoom 0 bis [maxZoom], die das Land
     * (plus [marginM] Rand) berühren. Sortiert.
     */
    fun tileIds(maxZoom: Int, marginM: Double = 3_000.0, minZoom: Int = 0): LongArray {
        val out = LongArrayBuilder()
        fun addAll(z: Int, x: Int, y: Int) {
            if (z >= minZoom) out.add(TileMath.zxyToTileId(z, x, y))
            if (z == maxZoom) return
            for (dx in 0..1) for (dy in 0..1) addAll(z + 1, 2 * x + dx, 2 * y + dy)
        }
        fun visit(z: Int, x: Int, y: Int) {
            val b = TileMath.bounds(z, x, y)
            if (!intersects(b[0], b[1], b[2], b[3], marginM)) return
            if (z >= 6 && containsRect(b[0], b[1], b[2], b[3])) { addAll(z, x, y); return }
            if (z >= minZoom) out.add(TileMath.zxyToTileId(z, x, y))
            if (z == maxZoom) return
            for (dx in 0..1) for (dy in 0..1) visit(z + 1, 2 * x + dx, 2 * y + dy)
        }
        visit(0, 0, 0)
        return out.toSortedArray()
    }

    /** Kachel-Koordinaten (z, x, y) einer Zoomstufe im Land. */
    fun tilesAt(z: Int, marginM: Double = 3_000.0): List<Triple<Int, Int, Int>> {
        val out = ArrayList<Triple<Int, Int, Int>>()
        fun visit(zz: Int, x: Int, y: Int) {
            val b = TileMath.bounds(zz, x, y)
            if (!intersects(b[0], b[1], b[2], b[3], marginM)) return
            if (zz == z) { out += Triple(zz, x, y); return }
            for (dx in 0..1) for (dy in 0..1) visit(zz + 1, 2 * x + dx, 2 * y + dy)
        }
        visit(0, 0, 0)
        return out
    }

    companion object {
        fun parseAll(input: InputStream): Map<String, CountryShape> {
            val root = Json.parseToJsonElement(input.bufferedReader().readText()).jsonObject
            val countries = root["countries"]!!.jsonObject
            return countries.mapValues { (cc, rings) ->
                CountryShape(
                    cc,
                    rings.jsonArray.map { ring ->
                        val pts = ring.jsonArray
                        Ring(
                            DoubleArray(pts.size) { pts[it].jsonArray[0].jsonPrimitive.content.toDouble() },
                            DoubleArray(pts.size) { pts[it].jsonArray[1].jsonPrimitive.content.toDouble() },
                        )
                    },
                )
            }
        }

    }
}

/** Wachsendes LongArray ohne Boxing. */
class LongArrayBuilder {
    private var a = LongArray(1024)
    var size = 0; private set
    fun add(v: Long) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
    fun toSortedArray(): LongArray = a.copyOf(size).also { it.sort() }
}

@Singleton
class CountryShapes @Inject constructor(@ApplicationContext private val context: Context) {
    val all: Map<String, CountryShape> by lazy { context.assets.open("country_shapes.json").use(CountryShape::parseAll) }
    fun of(code: String): CountryShape? = all[code.uppercase()]
}
