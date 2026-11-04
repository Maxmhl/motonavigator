package de.tetnavigator.feature.importer

import java.util.concurrent.TimeUnit

/** Alter-Warnung: GPX älter als einstellbare Tage (Standard 60). */
object GpxAge {
    fun ageDays(fileDate: Long, now: Long = System.currentTimeMillis()): Int =
        TimeUnit.MILLISECONDS.toDays((now - fileDate).coerceAtLeast(0)).toInt()

    fun isOld(fileDate: Long, maxAgeDays: Int, now: Long = System.currentTimeMillis()) = ageDays(fileDate, now) > maxAgeDays
}
