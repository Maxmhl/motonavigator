package de.tetnavigator.core.common

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Geographische Koordinate in Grad (WGS84). */
data class LatLon(val lat: Double, val lon: Double)

/** Trackpunkt mit optionaler Höhe (m) und Zeitstempel (epoch ms). */
data class GeoPoint(
    val lat: Double,
    val lon: Double,
    val ele: Double? = null,
    val time: Long? = null,
) {
    fun toLatLon() = LatLon(lat, lon)
}

data class BoundingBox(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double) {
    fun contains(lat: Double, lon: Double) = lat in minLat..maxLat && lon in minLon..maxLon
    val centerLat get() = (minLat + maxLat) / 2
    val centerLon get() = (minLon + maxLon) / 2

    fun expand(meters: Double): BoundingBox {
        val dLat = meters / Geo.METERS_PER_DEG_LAT
        val dLon = meters / (Geo.METERS_PER_DEG_LAT * cos(Math.toRadians(centerLat)).coerceAtLeast(0.01))
        return BoundingBox(minLat - dLat, minLon - dLon, maxLat + dLat, maxLon + dLon)
    }

    companion object {
        fun of(points: Iterable<GeoPoint>): BoundingBox? {
            var minLat = Double.MAX_VALUE; var minLon = Double.MAX_VALUE
            var maxLat = -Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
            var any = false
            for (p in points) {
                any = true
                if (p.lat < minLat) minLat = p.lat
                if (p.lat > maxLat) maxLat = p.lat
                if (p.lon < minLon) minLon = p.lon
                if (p.lon > maxLon) maxLon = p.lon
            }
            return if (any) BoundingBox(minLat, minLon, maxLat, maxLon) else null
        }
    }
}

/** Ergebnis einer Projektion eines Punktes auf ein Segment A→B. */
data class SegmentProjection(
    /** Anteil 0..1 entlang des Segments. */
    val t: Double,
    val lat: Double,
    val lon: Double,
    /** Abstand Punkt ↔ Projektion in Metern. */
    val distanceM: Double,
)

object Geo {
    const val EARTH_RADIUS_M = 6_371_008.8
    const val METERS_PER_DEG_LAT = 111_320.0

    fun distance(a: LatLon, b: LatLon) = distance(a.lat, a.lon, b.lat, b.lon)
    fun distance(a: GeoPoint, b: GeoPoint) = distance(a.lat, a.lon, b.lat, b.lon)

    /** Haversine-Distanz in Metern. */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val s = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * atan2(sqrt(s), sqrt(1 - s))
    }

    /** Anfangskurs von 1 nach 2 in Grad 0..360 (0 = Nord). */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return normalize360(Math.toDegrees(atan2(y, x)))
    }

    fun bearing(a: GeoPoint, b: GeoPoint) = bearing(a.lat, a.lon, b.lat, b.lon)

    fun normalize360(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0) d += 360.0
        return d
    }

    /** Vorzeichenbehaftete Winkeldifferenz b - a in -180..180 (positiv = rechts). */
    fun angleDiff(a: Double, b: Double): Double {
        var d = (b - a) % 360.0
        if (d > 180) d -= 360.0
        if (d < -180) d += 360.0
        return d
    }

    /**
     * Projiziert P auf das Segment A→B. Lokale equirektanguläre Näherung –
     * für Segmentlängen < einige km mehr als genau genug.
     */
    fun project(
        pLat: Double, pLon: Double,
        aLat: Double, aLon: Double,
        bLat: Double, bLon: Double,
    ): SegmentProjection {
        val cosLat = cos(Math.toRadians(pLat))
        val ax = (aLon - pLon) * cosLat; val ay = aLat - pLat
        val bx = (bLon - pLon) * cosLat; val by = bLat - pLat
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else ((-ax) * dx + (-ay) * dy) / len2
        val tc = t.coerceIn(0.0, 1.0)
        val lat = aLat + (bLat - aLat) * tc
        val lon = aLon + (bLon - aLon) * tc
        return SegmentProjection(tc, lat, lon, distance(pLat, pLon, lat, lon))
    }

    /** Punkt in Distanz d (m) und Kurs brg (Grad) von (lat, lon). */
    fun destination(lat: Double, lon: Double, brg: Double, d: Double): LatLon {
        val dr = d / EARTH_RADIUS_M
        val b = Math.toRadians(brg)
        val p1 = Math.toRadians(lat)
        val l1 = Math.toRadians(lon)
        val p2 = kotlin.math.asin(sin(p1) * cos(dr) + cos(p1) * sin(dr) * cos(b))
        val l2 = l1 + atan2(sin(b) * sin(dr) * cos(p1), cos(dr) - sin(p1) * sin(p2))
        return LatLon(Math.toDegrees(p2), ((Math.toDegrees(l2) + 540) % 360) - 180)
    }

    fun polylineLength(points: List<GeoPoint>): Double {
        var sum = 0.0
        for (i in 1 until points.size) sum += distance(points[i - 1], points[i])
        return sum
    }

    /** Kumulierte Distanzen (m) je Punkt, beginnend mit 0. */
    fun cumulative(points: List<GeoPoint>): DoubleArray {
        val out = DoubleArray(points.size)
        for (i in 1 until points.size) out[i] = out[i - 1] + distance(points[i - 1], points[i])
        return out
    }

    /**
     * Höhenmeter (Anstieg) mit Hysterese gegen GPS-/DEM-Rauschen:
     * Nur Änderungen über [thresholdM] werden gezählt.
     */
    fun ascent(elevations: List<Double?>, thresholdM: Double = 3.0): Double {
        var ascent = 0.0
        var ref: Double? = null
        for (e in elevations) {
            if (e == null) continue
            val r = ref
            if (r == null) { ref = e; continue }
            val d = e - r
            if (d >= thresholdM) { ascent += d; ref = e } else if (d <= -thresholdM) ref = e
        }
        return ascent
    }

    fun descent(elevations: List<Double?>, thresholdM: Double = 3.0) =
        ascent(elevations.map { it?.let { e -> -e } }, thresholdM)

    fun degToRad(d: Double) = d * PI / 180.0
    fun approxEquals(a: Double, b: Double, eps: Double) = abs(a - b) <= eps
}
