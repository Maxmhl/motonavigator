package de.tetnavigator.feature.logistik

import java.time.Instant

data class DaylightInfo(
    /** ms bis Sonnenuntergang (Long.MAX_VALUE = Mitternachtssonne). */
    val millisUntilDark: Long,
    val sunset: Instant?,
    /** Realistische Trail-km bis dunkel nach eigenem Offroad-Schnitt. */
    val trailKmUntilDark: Double,
    val avgKmh: Double,
    val measuredAvg: Boolean,
) {
    val polarDay get() = millisUntilDark == Long.MAX_VALUE
}

/** Tageslicht-Rechner: Sonnenuntergang + eigener gemessener Offroad-Schnitt. */
object DaylightCalculator {
    /** Eigener Offroad-Schnitt aus dem Logbuch, sonst Rückfallwert. */
    fun averageKmh(offroadM: Double?, offroadMovingMs: Long?, fallbackKmh: Double): Pair<Double, Boolean> {
        if (offroadM == null || offroadMovingMs == null || offroadM < 20_000 || offroadMovingMs < 30 * 60_000) return fallbackKmh to false
        val v = offroadM / 1000.0 / (offroadMovingMs / 3_600_000.0)
        return v.coerceIn(5.0, 90.0) to true
    }

    fun compute(now: Instant, lat: Double, lon: Double, avgKmh: Double, measured: Boolean): DaylightInfo {
        val ms = SunCalculator.millisUntilDark(now, lat, lon)
        val km = if (ms == Long.MAX_VALUE) Double.POSITIVE_INFINITY else ms / 3_600_000.0 * avgKmh
        val sunset = if (ms in 1 until Long.MAX_VALUE) now.plusMillis(ms) else null
        return DaylightInfo(ms, sunset, km, avgKmh, measured)
    }

    /** Ampel für „Licht“-Check: Fahrzeit vs. Zeit bis dunkel. */
    fun check(sectionKm: Double, avgKmh: Double, millisUntilDark: Long): CheckLevel {
        if (millisUntilDark == Long.MAX_VALUE) return CheckLevel.OK
        val needMs = (sectionKm / avgKmh * 3_600_000).toLong()
        return when {
            needMs <= millisUntilDark * 0.85 -> CheckLevel.OK
            needMs <= millisUntilDark -> CheckLevel.WARN
            else -> CheckLevel.BAD
        }
    }
}

enum class CheckLevel { OK, WARN, BAD }
