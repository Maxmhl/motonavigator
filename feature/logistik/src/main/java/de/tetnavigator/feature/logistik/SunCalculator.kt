package de.tetnavigator.feature.logistik

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Ergebnis für einen Tag. */
sealed interface SunTimes {
    data class Normal(val sunrise: Instant, val sunset: Instant, val solarNoon: Instant) : SunTimes
    /** Mitternachtssonne – geht nicht unter. */
    data class PolarDay(val solarNoon: Instant) : SunTimes
    /** Polarnacht – geht nicht auf. */
    data class PolarNight(val solarNoon: Instant) : SunTimes
}

/**
 * Sonnenauf- und -untergang nach dem NOAA-Algorithmus (Solar Calculator,
 * Meeus). Komplett offline, Genauigkeit ~1 min für |Breite| < 72°.
 */
object SunCalculator {
    /** 90,833° = Refraktion + Sonnenradius (offizieller Sonnenuntergang). */
    const val ZENITH_OFFICIAL = 90.833
    /** Bürgerliche Dämmerung. */
    const val ZENITH_CIVIL = 96.0

    fun compute(date: LocalDate, lat: Double, lon: Double, zenith: Double = ZENITH_OFFICIAL): SunTimes {
        val jd = date.toEpochDay() + 2440587.5 + 0.5 // Julianisches Datum 12:00 UTC
        val t = (jd - 2451545.0) / 36525.0

        val l0 = (280.46646 + t * (36000.76983 + t * 0.0003032)).mod(360.0)
        val m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val mr = Math.toRadians(m)
        val c = sin(mr) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2 * mr) * (0.019993 - 0.000101 * t) + sin(3 * mr) * 0.000289
        val trueLong = l0 + c
        val omega = 125.04 - 1934.136 * t
        val appLong = trueLong - 0.00569 - 0.00478 * sin(Math.toRadians(omega))
        val meanObliq = 23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60
        val obliqCorr = meanObliq + 0.00256 * cos(Math.toRadians(omega))
        val decl = Math.toDegrees(asin(sin(Math.toRadians(obliqCorr)) * sin(Math.toRadians(appLong))))

        val y = tan(Math.toRadians(obliqCorr / 2)).let { it * it }
        val l0r = Math.toRadians(l0)
        val eqTime = 4 * Math.toDegrees(
            y * sin(2 * l0r) - 2 * e * sin(mr) + 4 * e * y * sin(mr) * cos(2 * l0r) -
                0.5 * y * y * sin(4 * l0r) - 1.25 * e * e * sin(2 * mr),
        ) // Minuten

        val latR = Math.toRadians(lat); val declR = Math.toRadians(decl)
        val cosHa = cos(Math.toRadians(zenith)) / (cos(latR) * cos(declR)) - tan(latR) * tan(declR)
        val noonMin = 720 - 4 * lon - eqTime
        val midnight = date.atStartOfDay().toInstant(ZoneOffset.UTC)
        fun at(min: Double): Instant = midnight.plusMillis((min * 60_000).toLong())
        val noon = at(noonMin)
        return when {
            cosHa < -1 -> SunTimes.PolarDay(noon)
            cosHa > 1 -> SunTimes.PolarNight(noon)
            else -> {
                val ha = Math.toDegrees(acos(cosHa))
                SunTimes.Normal(at(noonMin - 4 * ha), at(noonMin + 4 * ha), noon)
            }
        }
    }

    /**
     * Millisekunden bis zum nächsten Sonnenuntergang ab [now]
     * (Long.MAX_VALUE bei Mitternachtssonne, 0 bei Dunkelheit/Polarnacht).
     */
    fun millisUntilDark(now: Instant, lat: Double, lon: Double, zenith: Double = ZENITH_OFFICIAL): Long {
        val today = now.atZone(ZoneOffset.UTC).toLocalDate()
        for (d in -1L..1L) {
            when (val s = compute(today.plusDays(d), lat, lon, zenith)) {
                is SunTimes.Normal -> {
                    if (now.isBefore(s.sunrise) && d >= 0) return 0
                    if (now.isBefore(s.sunset) && !now.isBefore(s.sunrise)) return s.sunset.toEpochMilli() - now.toEpochMilli()
                }
                is SunTimes.PolarDay -> if (d == 0L) return Long.MAX_VALUE
                is SunTimes.PolarNight -> if (d == 0L) return 0
            }
        }
        return 0
    }
}
