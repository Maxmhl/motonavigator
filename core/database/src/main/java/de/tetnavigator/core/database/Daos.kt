package de.tetnavigator.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Upsert suspend fun upsertCountry(c: CountryEntity)

    @Insert suspend fun insertFile(f: TrackFileEntity): Long
    @Insert suspend fun insertSection(s: SectionEntity): Long
    @Insert suspend fun insertPoints(points: List<TrackPointEntity>)
    @Insert suspend fun insertWaypoints(w: List<WaypointEntity>)

    @Query("UPDATE section SET gravelM = :gravel, dirtM = :dirt, asphaltM = :asphalt, unknownM = :unknown WHERE id = :sectionId")
    suspend fun updateSurfaceStats(sectionId: Long, gravel: Double, dirt: Double, asphalt: Double, unknown: Double)

    @Query("UPDATE track_point SET surface = :surface WHERE sectionId = :sectionId AND idx BETWEEN :fromIdx AND :toIdx")
    suspend fun updateSurface(sectionId: Long, fromIdx: Int, toIdx: Int, surface: Int)

    @Query("SELECT * FROM track_file WHERE id = :id") suspend fun file(id: Long): TrackFileEntity?
    @Query("SELECT * FROM track_file WHERE sha256 = :sha LIMIT 1") suspend fun fileBySha(sha: String): TrackFileEntity?
    @Query("SELECT * FROM track_file WHERE fileName = :name LIMIT 1") suspend fun fileByName(name: String): TrackFileEntity?
    @Query("SELECT * FROM track_file WHERE countryCode = :cc ORDER BY importedAt DESC") suspend fun filesByCountry(cc: String): List<TrackFileEntity>
    @Query("SELECT * FROM track_file") suspend fun allFiles(): List<TrackFileEntity>
    @Query("SELECT * FROM track_file ORDER BY countryCode") fun observeFiles(): Flow<List<TrackFileEntity>>
    @Query("DELETE FROM track_file WHERE id = :id") suspend fun deleteFile(id: Long)

    @Query("SELECT * FROM section WHERE id = :id") suspend fun section(id: Long): SectionEntity?
    @Query("SELECT * FROM section WHERE id = :id") fun observeSection(id: Long): Flow<SectionEntity?>
    @Query("SELECT * FROM section WHERE trackFileId = :fileId ORDER BY orderIndex") suspend fun sectionsOfFile(fileId: Long): List<SectionEntity>
    @Query("SELECT * FROM section ORDER BY countryCode, orderIndex") suspend fun allSections(): List<SectionEntity>
    @Query("SELECT * FROM section WHERE countryCode = :cc ORDER BY orderIndex") suspend fun sectionsOfCountry(cc: String): List<SectionEntity>
    @Query("SELECT * FROM section WHERE gravelM < 0") suspend fun sectionsWithoutSurface(): List<SectionEntity>

    @Query("SELECT * FROM track_point WHERE sectionId = :sectionId ORDER BY idx") suspend fun points(sectionId: Long): List<TrackPointEntity>
    @Query("SELECT * FROM track_point WHERE sectionId = :sectionId AND idx = :idx") suspend fun point(sectionId: Long, idx: Int): TrackPointEntity?
    @Query("SELECT * FROM track_point WHERE lat BETWEEN :minLat AND :maxLat AND lon BETWEEN :minLon AND :maxLon")
    suspend fun pointsInBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<TrackPointEntity>

    @Query("SELECT * FROM waypoint WHERE trackFileId = :fileId") suspend fun waypointsOfFile(fileId: Long): List<WaypointEntity>
    @Query("SELECT * FROM waypoint WHERE sectionId = :sectionId") suspend fun waypointsOfSection(sectionId: Long): List<WaypointEntity>
    @Query("SELECT COUNT(*) FROM waypoint WHERE trackFileId = :fileId") suspend fun waypointCount(fileId: Long): Int

    @Query(
        """
        SELECT s.countryCode AS countryCode, COUNT(s.id) AS sectionCount, SUM(s.lengthM) AS totalLengthM,
               COALESCE(SUM(CASE WHEN p.ridden = 1 THEN s.lengthM ELSE MIN(COALESCE(p.rideDistanceM, 0), s.lengthM) END), 0) AS riddenM,
               MIN(f.fileDate) AS oldestFileDate, COUNT(DISTINCT f.id) AS fileCount
        FROM section s JOIN track_file f ON f.id = s.trackFileId
        LEFT JOIN progress p ON p.sectionId = s.id
        GROUP BY s.countryCode ORDER BY s.countryCode
        """,
    )
    fun observeCountrySummaries(): Flow<List<CountrySummary>>

    @Query(
        """
        SELECT s.id, s.trackFileId, s.countryCode, s.name, s.lengthM, s.ascentM, s.descentM, s.orderIndex,
               p.ridden AS ridden, p.rideDistanceM AS rideDistanceM, f.fileDate AS fileDate, s.changedRanges
        FROM section s JOIN track_file f ON f.id = s.trackFileId
        LEFT JOIN progress p ON p.sectionId = s.id
        WHERE s.countryCode = :cc ORDER BY f.fileName, s.orderIndex
        """,
    )
    fun observeSectionsOfCountry(cc: String): Flow<List<SectionWithProgress>>

    @Query(
        """
        SELECT s.id, s.trackFileId, s.countryCode, s.name, s.lengthM, s.ascentM, s.descentM, s.orderIndex,
               p.ridden AS ridden, p.rideDistanceM AS rideDistanceM, f.fileDate AS fileDate, s.changedRanges
        FROM section s JOIN track_file f ON f.id = s.trackFileId
        LEFT JOIN progress p ON p.sectionId = s.id
        ORDER BY s.countryCode, f.fileName, s.orderIndex
        """,
    )
    fun observeAllSections(): Flow<List<SectionWithProgress>>

    @Query("SELECT COUNT(*) FROM section") fun observeSectionCount(): Flow<Int>
}

@Dao
interface ProgressDao {
    @Upsert suspend fun upsert(p: ProgressEntity)
    @Query("SELECT * FROM progress WHERE sectionId = :sectionId") suspend fun get(sectionId: Long): ProgressEntity?
    @Query("SELECT * FROM progress WHERE sectionId = :sectionId") fun observe(sectionId: Long): Flow<ProgressEntity?>
    @Query("SELECT * FROM progress") suspend fun all(): List<ProgressEntity>
    @Query("DELETE FROM progress WHERE sectionId = :sectionId") suspend fun delete(sectionId: Long)
}

@Dao
interface HazardDao {
    @Insert suspend fun insert(p: HazardPinEntity): Long
    @Query("DELETE FROM hazard_pin WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM hazard_pin ORDER BY createdAt DESC") fun observeAll(): Flow<List<HazardPinEntity>>
    @Query("SELECT * FROM hazard_pin ORDER BY createdAt DESC") suspend fun all(): List<HazardPinEntity>
    @Query("SELECT * FROM hazard_pin WHERE lat BETWEEN :minLat AND :maxLat AND lon BETWEEN :minLon AND :maxLon")
    suspend fun inBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<HazardPinEntity>
}

@Dao
interface RideDao {
    @Insert suspend fun insert(r: RideEntity): Long
    @Query("UPDATE ride SET endedAt = :endedAt, distanceM = :distanceM, offroadM = :offroadM, ascentM = :ascentM, movingMs = :movingMs, offroadMovingMs = :offroadMovingMs, title = :title WHERE id = :id")
    suspend fun finish(id: Long, endedAt: Long?, distanceM: Double, offroadM: Double, ascentM: Double, movingMs: Long, offroadMovingMs: Long, title: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertPoints(p: List<RidePointEntity>)
    @Upsert suspend fun upsertCountry(c: RideCountryEntity)
    @Query("SELECT * FROM ride WHERE id = :id") suspend fun ride(id: Long): RideEntity?
    @Query("SELECT * FROM ride WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1") suspend fun openRide(): RideEntity?
    @Query("SELECT * FROM ride ORDER BY startedAt DESC") fun observeRides(): Flow<List<RideEntity>>
    @Query("SELECT * FROM ride ORDER BY startedAt DESC") suspend fun rides(): List<RideEntity>
    @Query("SELECT * FROM ride_point WHERE rideId = :rideId ORDER BY idx") suspend fun points(rideId: Long): List<RidePointEntity>
    @Query("SELECT MAX(idx) FROM ride_point WHERE rideId = :rideId") suspend fun maxIdx(rideId: Long): Int?
    @Query("SELECT * FROM ride_country WHERE rideId = :rideId") suspend fun countriesOfRide(rideId: Long): List<RideCountryEntity>
    @Query("DELETE FROM ride WHERE id = :id") suspend fun delete(id: Long)

    @Query("SELECT countryCode, SUM(distanceM) AS distanceM, SUM(offroadM) AS offroadM FROM ride_country GROUP BY countryCode ORDER BY distanceM DESC")
    fun observeCountryStats(): Flow<List<CountryRideStat>>

    @Query("SELECT SUM(distanceM) AS distanceM, SUM(offroadM) AS offroadM, SUM(ascentM) AS ascentM, SUM(movingMs) AS movingMs, SUM(offroadMovingMs) AS offroadMovingMs FROM ride WHERE startedAt >= :since")
    fun observeTotals(since: Long): Flow<RideTotals>

    @Query("SELECT SUM(distanceM) AS distanceM, SUM(offroadM) AS offroadM, SUM(ascentM) AS ascentM, SUM(movingMs) AS movingMs, SUM(offroadMovingMs) AS offroadMovingMs FROM ride")
    suspend fun totals(): RideTotals

    @Query(
        """
        SELECT r.id, r.startedAt, r.endedAt, r.distanceM, r.offroadM, r.ascentM, r.sectionId, r.title,
          (SELECT COUNT(*) FROM journal_entry j WHERE j.rideId = r.id AND j.photoUri IS NOT NULL) AS photoCount,
          (SELECT COUNT(*) FROM journal_entry j WHERE j.rideId = r.id AND j.text != '') AS noteCount,
          (SELECT COUNT(*) FROM hazard_pin h WHERE h.createdAt BETWEEN r.startedAt AND COALESCE(r.endedAt, 9223372036854775807)) AS hazardCount
        FROM ride r ORDER BY r.startedAt DESC LIMIT :limit
        """,
    )
    fun observeRecent(limit: Int): Flow<List<RideWithCounts>>
}

@Dao
interface JournalDao {
    @Insert suspend fun insert(e: JournalEntryEntity): Long
    @Query("DELETE FROM journal_entry WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM journal_entry ORDER BY createdAt DESC") fun observeAll(): Flow<List<JournalEntryEntity>>
    @Query("SELECT * FROM journal_entry ORDER BY createdAt") suspend fun all(): List<JournalEntryEntity>
    @Query("SELECT * FROM journal_entry WHERE rideId = :rideId ORDER BY createdAt") suspend fun ofRide(rideId: Long): List<JournalEntryEntity>
}

@Dao
interface BlockedRangeDao {
    @Insert suspend fun insert(b: BlockedRangeEntity): Long
    @Query("DELETE FROM blocked_range WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM blocked_range WHERE sectionId = :sectionId ORDER BY startM") suspend fun ofSection(sectionId: Long): List<BlockedRangeEntity>
    @Query("SELECT * FROM blocked_range WHERE sectionId = :sectionId ORDER BY startM") fun observeOfSection(sectionId: Long): Flow<List<BlockedRangeEntity>>
}

@Dao
interface StageDao {
    @Query("SELECT * FROM stage_item ORDER BY dayIndex, orderIndex") fun observeAll(): Flow<List<StageItemEntity>>
    @Query("SELECT * FROM stage_item ORDER BY dayIndex, orderIndex") suspend fun all(): List<StageItemEntity>
    @Query("DELETE FROM stage_item") suspend fun clear()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(items: List<StageItemEntity>)

    @Transaction
    suspend fun replaceAll(items: List<StageItemEntity>) { clear(); insertAll(items) }
}
