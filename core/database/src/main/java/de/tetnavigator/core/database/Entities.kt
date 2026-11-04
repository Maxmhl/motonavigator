package de.tetnavigator.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "country")
data class CountryEntity(
    @PrimaryKey val code: String,
    val name: String,
)

@Entity(tableName = "track_file", indices = [Index("countryCode")])
data class TrackFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val countryCode: String,
    val fileName: String,
    val importedAt: Long,
    /** Datum der GPX (metadata/time, sonst erster Trackpunkt, sonst Datei-Änderungsdatum). */
    val fileDate: Long,
    val sha256: String,
    /** Anzahl geänderter Abschnitte gegenüber der Vorversion (Update-Vergleich). */
    val changedSegments: Int = 0,
    val previousFileDate: Long? = null,
)

@Entity(
    tableName = "section",
    foreignKeys = [ForeignKey(TrackFileEntity::class, ["id"], ["trackFileId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("trackFileId"), Index("countryCode")],
)
data class SectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackFileId: Long,
    val countryCode: String,
    val name: String,
    val lengthM: Double,
    val ascentM: Double,
    val descentM: Double,
    val orderIndex: Int,
    val pointCount: Int,
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
    val maxEle: Double?,
    val maxEleDistM: Double?,
    /** Geänderte Abschnitte gegenüber Vorversion, Format "start-end;start-end" in Metern. */
    val changedRanges: String = "",
    /** Untergrund-Meter (nach Analyse mit Offline-Daten), -1 = noch nicht analysiert. */
    val gravelM: Double = -1.0,
    val dirtM: Double = -1.0,
    val asphaltM: Double = -1.0,
    val unknownM: Double = -1.0,
)

/** Untergrund-Codes je Trackpunkt (Segment idx → idx+1). */
object SurfaceCode {
    const val UNKNOWN = 0
    const val GRAVEL = 1
    const val DIRT = 2
    const val ASPHALT = 3
}

@Entity(
    tableName = "track_point",
    primaryKeys = ["sectionId", "idx"],
    foreignKeys = [ForeignKey(SectionEntity::class, ["id"], ["sectionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("lat")],
)
data class TrackPointEntity(
    val sectionId: Long,
    val idx: Int,
    val lat: Double,
    val lon: Double,
    val ele: Double?,
    /** Kumulierte Distanz ab Sektionsstart (m). */
    val distM: Double,
    val surface: Int = SurfaceCode.UNKNOWN,
    /** Segment gegenüber Vorversion geändert (gelb markieren). */
    val changed: Boolean = false,
)

@Entity(
    tableName = "waypoint",
    foreignKeys = [ForeignKey(TrackFileEntity::class, ["id"], ["trackFileId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sectionId"), Index("trackFileId")],
)
data class WaypointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackFileId: Long,
    val sectionId: Long?,
    val lat: Double,
    val lon: Double,
    val name: String,
    val type: String,
)

@Entity(tableName = "progress")
data class ProgressEntity(
    @PrimaryKey val sectionId: Long,
    val ridden: Boolean,
    /** Gefahrene Strecke auf der Sektion (m, max. Fortschritt). */
    val rideDistanceM: Double,
    val updatedAt: Long,
)

object HazardType {
    const val FORD = "FURT"
    const val LANDSLIDE = "ERDRUTSCH"
    const val GATE = "TOR"
    const val CLOSURE = "SPERRUNG"
    const val OTHER = "SONSTIGES"
    val all = listOf(FORD, LANDSLIDE, GATE, CLOSURE, OTHER)
}

@Entity(tableName = "hazard_pin", indices = [Index("lat")])
data class HazardPinEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lon: Double,
    val type: String,
    val note: String,
    val photoUri: String?,
    val createdAt: Long,
)

@Entity(tableName = "ride", indices = [Index("startedAt")])
data class RideEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long?,
    val distanceM: Double,
    val offroadM: Double,
    val ascentM: Double,
    val sectionId: Long? = null,
    val title: String = "",
    /** Gefahrene Zeit (ohne Stillstand) für den eigenen Offroad-Schnitt. */
    val movingMs: Long = 0,
    val offroadMovingMs: Long = 0,
)

@Entity(
    tableName = "ride_point",
    primaryKeys = ["rideId", "idx"],
    foreignKeys = [ForeignKey(RideEntity::class, ["id"], ["rideId"], onDelete = ForeignKey.CASCADE)],
)
data class RidePointEntity(
    val rideId: Long,
    val idx: Int,
    val lat: Double,
    val lon: Double,
    val ele: Double?,
    val time: Long,
    val speedMs: Float,
    val countryCode: String?,
    val offroad: Boolean,
)

/** km pro Land je Fahrt. */
@Entity(tableName = "ride_country", primaryKeys = ["rideId", "countryCode"])
data class RideCountryEntity(
    val rideId: Long,
    val countryCode: String,
    val distanceM: Double,
    val offroadM: Double,
)

@Entity(tableName = "journal_entry", indices = [Index("rideId")])
data class JournalEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rideId: Long?,
    val lat: Double,
    val lon: Double,
    val text: String,
    val photoUri: String?,
    val createdAt: Long,
)

/** Als gesperrt markierter Abschnitt einer Sektion (Umfahrung). */
@Entity(tableName = "blocked_range", indices = [Index("sectionId")])
data class BlockedRangeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sectionId: Long,
    val startM: Double,
    val endM: Double,
    val note: String,
    val createdAt: Long,
)

/** Etappenplaner: Sektion in Tagesetappe. */
@Entity(tableName = "stage_item", primaryKeys = ["sectionId"])
data class StageItemEntity(
    val sectionId: Long,
    val dayIndex: Int,
    val orderIndex: Int,
    val reversed: Boolean = false,
)
