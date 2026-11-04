package de.tetnavigator.feature.navigation

/**
 * Abweich-Alarm mit Hysterese (Spezifikation 6, Schritt 4):
 * Alarm erst, wenn Abstand > Schwellwert länger als [holdMs] (5 s, gegen
 * GPS-Sprünge im Wald). Zurück auf Track erst bei < Schwellwert × 0,5.
 */
class DeviationMonitor(
    var thresholdM: Double = 40.0,
    private val holdMs: Long = 5_000,
) {
    enum class State { ON_TRACK, SUSPECT, OFF_TRACK }

    var state: State = State.ON_TRACK
        private set
    private var exceededSince: Long? = null

    /** @return true genau in dem Moment, in dem der Alarm auslöst. */
    fun update(distanceM: Double, timeMs: Long): Boolean {
        when (state) {
            State.ON_TRACK, State.SUSPECT -> {
                if (distanceM > thresholdM) {
                    val since = exceededSince ?: timeMs.also { exceededSince = it }
                    if (timeMs - since >= holdMs) {
                        state = State.OFF_TRACK
                        return true
                    }
                    state = State.SUSPECT
                } else {
                    exceededSince = null
                    state = State.ON_TRACK
                }
            }
            State.OFF_TRACK -> {
                if (distanceM < thresholdM * 0.5) {
                    state = State.ON_TRACK
                    exceededSince = null
                }
            }
        }
        return false
    }

    fun reset() { state = State.ON_TRACK; exceededSince = null }
}

/** Auto-Zoom nach Tempo (km/h → MapLibre-Zoomstufe). */
object AutoZoom {
    fun zoomFor(speedKmh: Double): Double = when {
        speedKmh < 8 -> 16.8
        speedKmh < 20 -> 16.2
        speedKmh < 35 -> 15.6
        speedKmh < 55 -> 15.0
        speedKmh < 80 -> 14.3
        else -> 13.6
    }

    /** Glättet Zoomsprünge. */
    fun smooth(current: Double, target: Double, factor: Double = 0.25) = current + (target - current) * factor
}
