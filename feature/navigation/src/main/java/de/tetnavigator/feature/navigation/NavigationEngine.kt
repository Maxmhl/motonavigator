package de.tetnavigator.feature.navigation

import de.tetnavigator.core.common.Geo
import de.tetnavigator.core.offlinedata.SurfaceClass

/** Eingabe-Position (aus dem GNSS-Foreground-Service). */
data class Fix(
    val lat: Double,
    val lon: Double,
    val timeMs: Long,
    val speedMs: Float,
    val bearing: Float?,
    val accuracyM: Float,
    val altitude: Double?,
)

enum class ManeuverKind { STRAIGHT, CURVE, HAIRPIN, FINISH }

data class Maneuver(
    val kind: ManeuverKind,
    val side: TurnSide?,
    val distanceM: Double,
    /** Stencil-Hinweis, z. B. „KEHRE RECHTS · DANN SCHOTTER“. */
    val hint: String,
)

data class NavState(
    val fix: Fix,
    val match: MatchResult?,
    val track: ActiveTrack,
    val deviation: DeviationMonitor.State,
    val deviationM: Double,
    /** Kurs zurück zum Projektionspunkt (bei Abweichung). */
    val bearingBack: Double?,
    val remainingM: Double,
    val progressFraction: Double,
    val maneuver: Maneuver,
    val zoom: Double,
    val alarmTriggered: Boolean,
    val finished: Boolean,
)

/**
 * Kern „Track folgen“: Matching → Fortschritt → Abweichung → nächstes Manöver.
 * Reine Logik, keine Android-Abhängigkeit (gut testbar).
 */
class NavigationEngine(val track: ActiveTrack, thresholdM: Double) {
    private val matcher = TrackMatcher(track)
    val deviation = DeviationMonitor(thresholdM)
    private var zoom = 15.5

    var maxAlongM = 0.0
        private set

    fun setThreshold(m: Double) { deviation.thresholdM = m }

    fun update(fix: Fix): NavState {
        val m = matcher.match(fix.lat, fix.lon, fix.timeMs, fix.speedMs, fix.bearing)
        val devM = m?.distanceM ?: Double.MAX_VALUE
        val alarm = deviation.update(devM, fix.timeMs)
        val along = m?.alongM ?: 0.0
        if (m != null && deviation.state != DeviationMonitor.State.OFF_TRACK) maxAlongM = maxOf(maxAlongM, along)
        val back = m?.let { Geo.bearing(fix.lat, fix.lon, it.projLat, it.projLon) }
        zoom = AutoZoom.smooth(zoom, AutoZoom.zoomFor(fix.speedMs * 3.6))
        val remaining = (track.lengthM - along).coerceAtLeast(0.0)
        return NavState(
            fix = fix,
            match = m,
            track = track,
            deviation = deviation.state,
            deviationM = devM,
            bearingBack = back,
            remainingM = remaining,
            progressFraction = if (track.lengthM > 0) (maxAlongM / track.lengthM).coerceIn(0.0, 1.0) else 0.0,
            maneuver = nextManeuver(along),
            zoom = zoom,
            alarmTriggered = alarm,
            finished = remaining < 40 && maxAlongM > track.lengthM * 0.8,
        )
    }

    fun nextManeuver(alongM: Double): Maneuver {
        val next = track.turns.firstOrNull { it.distM > alongM + 5 }
        val toEnd = track.lengthM - alongM
        if (next == null || next.distM - alongM > toEnd) {
            return Maneuver(ManeuverKind.FINISH, null, toEnd.coerceAtLeast(0.0), "ENDE SEKTION")
        }
        val dist = next.distM - alongM
        val kind = if (next.kind == TurnKind.HAIRPIN) ManeuverKind.HAIRPIN else ManeuverKind.CURVE
        val side = if (next.side == TurnSide.RIGHT) "RECHTS" else "LINKS"
        val base = if (next.kind == TurnKind.HAIRPIN) "KEHRE $side" else "KURVE $side"
        val surfaceHint = track.nextSurfaceChange(next.distM, 300.0)?.let { (_, code) ->
            when (SurfaceClass.ofCode(code)) {
                SurfaceClass.GRAVEL -> "DANN SCHOTTER"
                SurfaceClass.DIRT -> "DANN ERDE"
                SurfaceClass.ASPHALT -> "DANN ASPHALT"
                SurfaceClass.UNKNOWN -> null
            }
        }
        return if (dist > 2_000) {
            Maneuver(ManeuverKind.STRAIGHT, null, dist, "TRACK FOLGEN")
        } else {
            Maneuver(kind, next.side, dist, listOfNotNull(base, surfaceHint).joinToString(" · "))
        }
    }
}
