package de.tetnavigator.feature.logbuch

import de.tetnavigator.core.common.Countries
import de.tetnavigator.core.database.CountrySummary
import de.tetnavigator.core.database.HazardPinEntity
import de.tetnavigator.core.database.JournalEntryEntity
import de.tetnavigator.core.database.RideTotals
import de.tetnavigator.core.database.RideWithCounts
import de.tetnavigator.core.database.TetDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Länderstempel: gefahrene Länder/Sektionen, Fortschritt pro Land in %. */
data class CountryStamp(
    val code: String,
    val name: String,
    /** null = noch nicht gefahren */
    val percent: Int?,
    val riddenKm: Double,
    val sections: Int,
    val sectionsRidden: Int,
)

data class LogbookOverview(
    val trailKm: Double,
    val offroadFraction: Double,
    val ascentM: Double,
    val stamps: List<CountryStamp>,
    val recent: List<RideWithCounts>,
)

@Singleton
class LogbuchRepository @Inject constructor(private val db: TetDatabase) {

    fun seasonStart(now: LocalDate = LocalDate.now()): Long =
        now.withDayOfYear(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun overview(since: Long = seasonStart()): Flow<LogbookOverview> = combine(
        db.rideDao().observeTotals(since),
        db.trackDao().observeCountrySummaries(),
        db.trackDao().observeAllSections(),
        db.rideDao().observeCountryStats(),
        db.rideDao().observeRecent(10),
    ) { totals: RideTotals, countries: List<CountrySummary>, sections, rideStats, recent ->
        val dist = totals.distanceM ?: 0.0
        val ridden = rideStats.associateBy { it.countryCode }
        val codes = (countries.map { it.countryCode } + rideStats.map { it.countryCode }).distinct()
        val stamps = codes.map { cc ->
            val sum = countries.firstOrNull { it.countryCode == cc }
            val sec = sections.filter { it.countryCode == cc }
            val pct = when {
                sum != null && sum.totalLengthM > 0 -> (sum.riddenM / sum.totalLengthM * 100).toInt().coerceIn(0, 100)
                else -> null
            }
            val riddenKm = (ridden[cc]?.distanceM ?: 0.0) / 1000
            CountryStamp(
                code = cc, name = Countries.name(cc),
                percent = pct?.takeIf { it > 0 || riddenKm > 0 } ?: if (riddenKm > 0) 1 else null,
                riddenKm = riddenKm, sections = sec.size, sectionsRidden = sec.count { it.ridden == true },
            )
        }.sortedWith(compareByDescending<CountryStamp> { it.percent ?: -1 }.thenBy { it.code })
        LogbookOverview(dist / 1000, if (dist > 0) (totals.offroadM ?: 0.0) / dist else 0.0, totals.ascentM ?: 0.0, stamps, recent)
    }

    // --- Reisetagebuch & Gefahren-Pins ---------------------------------------

    suspend fun addJournal(rideId: Long?, lat: Double, lon: Double, text: String, photoUri: String?): Long =
        db.journalDao().insert(JournalEntryEntity(rideId = rideId, lat = lat, lon = lon, text = text, photoUri = photoUri, createdAt = System.currentTimeMillis()))

    suspend fun deleteJournal(id: Long) = db.journalDao().delete(id)
    fun journal(): Flow<List<JournalEntryEntity>> = db.journalDao().observeAll()

    suspend fun addHazard(lat: Double, lon: Double, type: String, note: String, photoUri: String?): Long =
        db.hazardDao().insert(HazardPinEntity(lat = lat, lon = lon, type = type, note = note, photoUri = photoUri, createdAt = System.currentTimeMillis()))

    suspend fun deleteHazard(id: Long) = db.hazardDao().delete(id)
    fun hazards(): Flow<List<HazardPinEntity>> = db.hazardDao().observeAll()

    suspend fun deleteRide(id: Long) = db.rideDao().delete(id)
}
