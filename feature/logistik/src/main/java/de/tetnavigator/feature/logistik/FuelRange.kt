package de.tetnavigator.feature.logistik

/** Tankstelle entlang der Sektion (Distanz in Fahrtrichtung). */
data class StationOnTrack(val alongM: Double, val name: String, val offsetM: Double)

sealed interface FuelWarning {
    data object Ok : FuelWarning
    /** Nächste Tankstelle ist die letzte vor einer langen Lücke. */
    data class LastStationBeforeGap(val stationInM: Double, val gapM: Double, val name: String) : FuelWarning
    /** Reichweite reicht nicht bis zur nächsten Tankstelle bzw. zum Ende. */
    data class OutOfRange(val rangeM: Double, val nextStationM: Double?) : FuelWarning
    /** Reichweite unter 25 % des Tanks. */
    data class Low(val rangeM: Double) : FuelWarning
}

/**
 * Tank-Reichweite: Tankgröße (l) + Verbrauch (l/100 km) → Reichweite;
 * Warnung vor der letzten Tankstelle vor langen Schotterstücken.
 */
object FuelRange {
    fun rangeM(fuelL: Double, consumptionLPer100: Double): Double =
        if (consumptionLPer100 <= 0) 0.0 else fuelL / consumptionLPer100 * 100_000.0

    fun consumedLiters(distanceM: Double, consumptionLPer100: Double) = distanceM / 100_000.0 * consumptionLPer100

    /**
     * @param alongM aktuelle Position entlang der Sektion
     * @param lengthM Länge der Sektion
     * @param stations Tankstellen entlang der Sektion (sortiert)
     * @param fullRangeM Reichweite mit vollem Tank
     */
    fun evaluate(alongM: Double, lengthM: Double, rangeM: Double, fullRangeM: Double, stations: List<StationOnTrack>): FuelWarning {
        val ahead = stations.filter { it.alongM > alongM }.sortedBy { it.alongM }
        val next = ahead.firstOrNull()
        val toEnd = lengthM - alongM
        if (next == null) {
            if (rangeM < toEnd) return FuelWarning.OutOfRange(rangeM, null)
        } else if (rangeM < next.alongM - alongM) {
            return FuelWarning.OutOfRange(rangeM, next.alongM - alongM)
        }
        // Lücke nach der nächsten Tankstelle
        if (next != null && next.alongM - alongM <= rangeM) {
            val after = ahead.getOrNull(1)?.alongM ?: lengthM
            val gap = after - next.alongM
            // „lang“ = mehr als 60 % der vollen Reichweite oder mehr als aktuelle Restreichweite ab dort
            if (gap > fullRangeM * 0.6 || gap > rangeM - (next.alongM - alongM)) {
                return FuelWarning.LastStationBeforeGap(next.alongM - alongM, gap, next.name)
            }
        }
        if (rangeM < fullRangeM * 0.25) return FuelWarning.Low(rangeM)
        return FuelWarning.Ok
    }

    /** Ampel für Sektions-Details: reicht der Tank für die Sektion (mit Tankstellen unterwegs)? */
    fun check(lengthM: Double, rangeM: Double, fullRangeM: Double, stations: List<StationOnTrack>): CheckLevel {
        var pos = 0.0
        var r = rangeM
        val sorted = stations.sortedBy { it.alongM }
        for (s in sorted) {
            if (s.alongM - pos > r) return CheckLevel.BAD
            r = fullRangeM; pos = s.alongM
        }
        val rest = lengthM - pos
        return when {
            rest > r -> CheckLevel.BAD
            rest > r * 0.8 -> CheckLevel.WARN
            else -> CheckLevel.OK
        }
    }
}
