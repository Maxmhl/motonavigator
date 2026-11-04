package de.tetnavigator.core.common

import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Deutsche Zahlenformate für UI (Komma, Tausenderpunkt). */
object Format {
    private val de = Locale.GERMANY

    /** 240 m · 1,2 km · 62 km */
    fun distance(meters: Double): String = when {
        meters < 0 -> "–"
        meters < 1000 -> "${(meters / 10).roundToInt() * 10} m"
        meters < 10_000 -> String.format(de, "%.1f km", meters / 1000)
        else -> "${thousands((meters / 1000).roundToLong())} km"
    }

    /** Distanz-Zahl ohne Einheit, km mit einer Nachkommastelle (62,4). */
    fun km1(meters: Double): String = String.format(de, "%.1f", meters / 1000)
    fun km0(meters: Double): String = thousands((meters / 1000).roundToLong())

    fun thousands(v: Long): String = String.format(de, "%,d", v)

    /** 18,9k */
    fun compact(v: Double): String = if (v >= 10_000) String.format(de, "%.1fk", v / 1000) else thousands(v.roundToLong())

    /** Dauer h:mm */
    fun hm(millis: Long): String {
        if (millis < 0) return "–"
        val totalMin = millis / 60_000
        return String.format(de, "%d:%02d", totalMin / 60, totalMin % 60)
    }

    fun percent(fraction: Double): String = "${(fraction * 100).roundToInt()} %"
    fun meters(m: Double): String = "${thousands(m.roundToLong())} m"
}
