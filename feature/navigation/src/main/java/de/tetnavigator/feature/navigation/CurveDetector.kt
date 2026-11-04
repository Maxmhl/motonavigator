package de.tetnavigator.feature.navigation

import de.tetnavigator.core.common.Geo
import kotlin.math.abs

enum class TurnKind { CURVE, HAIRPIN }
enum class TurnSide { LEFT, RIGHT }

data class TurnEvent(
    val distM: Double,
    val kind: TurnKind,
    val side: TurnSide,
    /** Richtungsänderung in Grad (Betrag). */
    val angle: Double,
)

/**
 * Kurven-Vorschau (Spezifikation 6, Schritt 5): Richtungsänderung über ein
 * gleitendes Fenster (Standard 30 m); > 60° = Kurve, > 120° = Kehre.
 */
class CurveDetector(
    private val windowM: Double = 30.0,
    private val curveDeg: Double = 60.0,
    private val hairpinDeg: Double = 120.0,
    private val stepM: Double = 5.0,
    /** Mindestabstand zweier gemeldeter Ereignisse. */
    private val suppressM: Double = 40.0,
) {
    fun detect(v: List<TrackVertex>): List<TurnEvent> {
        if (v.size < 3) return emptyList()
        val total = v.last().distM
        // 1. gleichmäßig neu abtasten
        val lat = ArrayList<Double>(); val lon = ArrayList<Double>(); val dist = ArrayList<Double>()
        var i = 0
        var d = 0.0
        while (d <= total) {
            while (i < v.size - 2 && v[i + 1].distM < d) i++
            val a = v[i]; val b = v[i + 1]
            val f = if (b.distM > a.distM) ((d - a.distM) / (b.distM - a.distM)).coerceIn(0.0, 1.0) else 0.0
            lat += a.lat + (b.lat - a.lat) * f; lon += a.lon + (b.lon - a.lon) * f; dist += d
            d += stepM
        }
        val n = lat.size
        val half = (windowM / 2 / stepM).toInt().coerceAtLeast(1)
        if (n < 2 * half + 1) return emptyList()
        // 2. lokale Fahrtrichtung je Stützpunkt (5-m-Sehne)
        val brg = DoubleArray(n)
        for (k in 0 until n - 1) brg[k] = Geo.bearing(lat[k], lon[k], lat[k + 1], lon[k + 1])
        brg[n - 1] = brg[n - 2]
        // 3. Richtungsänderung über das Fenster: Tangente bei s − w/2 gegen s + w/2
        val delta = DoubleArray(n)
        for (k in half until n - half) delta[k] = Geo.angleDiff(brg[k - half], brg[k + half])
        // 4. Läufe über Schwelle → ein Ereignis, Gesamtwinkel des Laufs
        val events = ArrayList<TurnEvent>()
        var k = half
        while (k < n - half) {
            if (abs(delta[k]) < curveDeg) { k++; continue }
            val sign = delta[k] > 0
            var best = k
            var j = k
            while (j < n - half && abs(delta[j]) >= curveDeg * 0.5 && (delta[j] > 0) == sign) {
                if (abs(delta[j]) > abs(delta[best])) best = j
                j++
            }
            val from = (k - half).coerceAtLeast(0)
            val to = (j - 1 + half).coerceAtMost(n - 1)
            // aufsummierte Drehung (vorzeichenrichtig) über den Lauf
            var turn = 0.0
            for (q in from until to) turn += Geo.angleDiff(brg[q], brg[q + 1])
            val total = maxOf(abs(delta[best]), abs(turn))
            val ev = TurnEvent(
                distM = dist[best],
                kind = if (total > hairpinDeg) TurnKind.HAIRPIN else TurnKind.CURVE,
                side = if (sign) TurnSide.RIGHT else TurnSide.LEFT,
                angle = total,
            )
            if (events.isEmpty() || ev.distM - events.last().distM >= suppressM) events += ev
            else if (ev.angle > events.last().angle) events[events.size - 1] = ev
            k = j + 1
        }
        return events
    }
}
