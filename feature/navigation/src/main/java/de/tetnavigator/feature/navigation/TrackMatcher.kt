package de.tetnavigator.feature.navigation

import de.tetnavigator.core.common.Geo
import kotlin.math.abs
import kotlin.math.max

data class MatchResult(
    val segment: Int,
    /** Fortschritt = kumulierte Distanz bis zum Projektionspunkt. */
    val alongM: Double,
    val projLat: Double,
    val projLon: Double,
    /** Abweichung = Abstand Position ↔ Projektion. */
    val distanceM: Double,
    val segmentBearing: Double,
)

/**
 * Map-Matching (Spezifikation 6 „Track folgen“):
 * Jede Position wird auf das nächste Segment in Fahrtrichtung projiziert.
 * Kandidaten zuerst nur im Umkreis der letzten Position (entlang des Tracks),
 * damit Kreuzungen und Kehren nicht verwechselt werden.
 */
class TrackMatcher(private val track: ActiveTrack) {
    private var last: MatchResult? = null
    private var lastTime = 0L

    fun reset() { last = null }

    fun match(lat: Double, lon: Double, timeMs: Long, speedMs: Float, bearing: Float?): MatchResult? {
        val v = track.vertices
        if (v.size < 2) return null
        val prev = last
        val dt = if (lastTime == 0L) 1.0 else ((timeMs - lastTime) / 1000.0).coerceIn(0.2, 60.0)
        lastTime = timeMs

        var result: MatchResult? = null
        if (prev != null) {
            // Fenster entlang des Tracks: etwas zurück, nach vorn je nach Tempo.
            val back = 60.0
            val ahead = max(250.0, speedMs * dt * 3 + 100)
            result = bestIn(lat, lon, bearing, speedMs, prev.alongM - back, prev.alongM + ahead, null)
            // Nur akzeptieren, wenn plausibel nah – sonst global neu suchen.
            if (result != null && result.distanceM > max(150.0, speedMs * dt * 2)) result = null
        }
        if (result == null) {
            var r = 150.0
            while (result == null && r <= 3_000.0) {
                val cand = track.segmentsNear(lat, lon, r)
                if (cand.isNotEmpty()) result = bestIn(lat, lon, bearing, speedMs, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, cand)
                r *= 2.5
            }
            if (result == null) result = bestIn(lat, lon, bearing, speedMs, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, (0 until v.size - 1).toSet())
        }
        last = result
        return result
    }

    private fun bestIn(
        lat: Double, lon: Double, bearing: Float?, speedMs: Float,
        fromM: Double, toM: Double, candidates: Set<Int>?,
    ): MatchResult? {
        val v = track.vertices
        val range: Iterable<Int> = candidates ?: run {
            val a = track.indexAt(fromM.coerceAtLeast(0.0))
            val b = track.indexAt(toM.coerceAtMost(track.lengthM))
            a..b
        }
        var best: MatchResult? = null
        var bestScore = Double.MAX_VALUE
        for (i in range) {
            if (i < 0 || i >= v.size - 1) continue
            val a = v[i]; val b = v[i + 1]
            val p = Geo.project(lat, lon, a.lat, a.lon, b.lat, b.lon)
            val segB = Geo.bearing(a.lat, a.lon, b.lat, b.lon)
            // Fahrtrichtung: Segmente gegen die Bewegungsrichtung bestrafen (nur bei Fahrt).
            val dirPenalty = if (bearing != null && speedMs > 2.5f && abs(Geo.angleDiff(bearing.toDouble(), segB)) > 100) 40.0 else 0.0
            val score = p.distanceM + dirPenalty
            if (score < bestScore) {
                bestScore = score
                best = MatchResult(i, a.distM + (b.distM - a.distM) * p.t, p.lat, p.lon, p.distanceM, segB)
            }
        }
        return best
    }
}
