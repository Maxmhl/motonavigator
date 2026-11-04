package de.tetnavigator.core.database

/** Zusammenfassung je Land für die Track-Bibliothek. */
data class CountrySummary(
    val countryCode: String,
    val sectionCount: Int,
    val totalLengthM: Double,
    val riddenM: Double,
    /** Ältestes Dateidatum (für Alter-Warnung). */
    val oldestFileDate: Long?,
    val fileCount: Int,
)

data class SectionWithProgress(
    val id: Long,
    val trackFileId: Long,
    val countryCode: String,
    val name: String,
    val lengthM: Double,
    val ascentM: Double,
    val descentM: Double,
    val orderIndex: Int,
    val ridden: Boolean?,
    val rideDistanceM: Double?,
    val fileDate: Long,
    val changedRanges: String,
)

data class CountryRideStat(
    val countryCode: String,
    val distanceM: Double,
    val offroadM: Double,
)

data class RideTotals(
    val distanceM: Double?,
    val offroadM: Double?,
    val ascentM: Double?,
    val movingMs: Long?,
    val offroadMovingMs: Long?,
)

data class RideWithCounts(
    val id: Long,
    val startedAt: Long,
    val endedAt: Long?,
    val distanceM: Double,
    val offroadM: Double,
    val ascentM: Double,
    val sectionId: Long?,
    val title: String,
    val photoCount: Int,
    val noteCount: Int,
    val hazardCount: Int,
)

/** Treffer im räumlichen Index. */
data class SegmentRef(val sectionId: Long, val idx: Int)
