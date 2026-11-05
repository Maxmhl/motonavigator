package de.tetnavigator.core.offlinedata

import android.database.sqlite.SQLiteDatabase
import de.tetnavigator.core.common.Geo
import de.tetnavigator.core.common.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos

/** POI-Kategorien (Camping & Versorgung). */
enum class PoiCategory(val id: String, val labelDe: String) {
    CAMPSITE("campsite", "Zeltplatz"),
    HUT("hut", "Hütte"),
    WATER("water", "Wasser"),
    SUPERMARKET("supermarket", "Supermarkt"),
    WORKSHOP("workshop", "Werkstatt"),
    FUEL("fuel", "Tankstelle"),
    ;

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id }
    }
}

data class Poi(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val category: PoiCategory,
    val name: String,
    val country: String,
)

/** Weg mit Belag-Angaben aus OSM (für Untergrund-Färbung). */
data class SurfaceWay(
    val surface: String?,
    val tracktype: String?,
    val highway: String?,
    val coords: List<LatLon>,
)

/**
 * Zugriff auf die POI-SQLite-Pakete (eins pro Land, read-only).
 *
 * Schema (erzeugt von tools/build-packages/build_poi.py):
 * ```
 * poi(id INTEGER PRIMARY KEY, lat REAL, lon REAL, category TEXT, name TEXT)
 * poi_rtree(id, minLat, maxLat, minLon, maxLon)                -- optional
 * way_surface(id INTEGER PRIMARY KEY, surface TEXT, tracktype TEXT, highway TEXT, coords TEXT)
 * way_rtree(id, minLat, maxLat, minLon, maxLon)
 * ```
 * coords = "lat,lon;lat,lon;…"
 */
@Singleton
class PoiDatabase @Inject constructor(private val storage: OfflineStorage) {

    private val open = HashMap<String, SQLiteDatabase>()

    @Synchronized
    private fun dbs(): List<Pair<String, SQLiteDatabase>> {
        val files = storage.poiFiles()
        for ((cc, f) in files) {
            if (open[f.path] == null) openDb(f)?.let { open[f.path] = it }
        }
        return files.mapNotNull { (cc, f) -> open[f.path]?.let { cc to it } }
    }

    private fun openDb(f: File): SQLiteDatabase? = try {
        SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
    } catch (_: Exception) {
        null
    }

    private fun box(lat: Double, lon: Double, r: Double): DoubleArray {
        val dLat = r / Geo.METERS_PER_DEG_LAT
        val dLon = r / (Geo.METERS_PER_DEG_LAT * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        return doubleArrayOf(lat - dLat, lat + dLat, lon - dLon, lon + dLon)
    }

    private fun hasTable(db: SQLiteDatabase, name: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE name = ?", arrayOf(name)).use { it.moveToFirst() }

    suspend fun near(lat: Double, lon: Double, radiusM: Double, categories: Set<PoiCategory> = PoiCategory.entries.toSet()): List<Poi> =
        withContext(Dispatchers.IO) {
            val b = box(lat, lon, radiusM)
            val out = ArrayList<Poi>()
            for ((cc, db) in dbs()) {
                val catSql = categories.joinToString(",") { "'${it.id}'" }
                val sql = if (hasTable(db, "poi_rtree")) {
                    "SELECT p.id, p.lat, p.lon, p.category, p.name FROM poi p JOIN poi_rtree r ON r.id = p.id " +
                        "WHERE r.minLat >= ? AND r.maxLat <= ? AND r.minLon >= ? AND r.maxLon <= ? AND p.category IN ($catSql)"
                } else {
                    "SELECT id, lat, lon, category, name FROM poi WHERE lat >= ? AND lat <= ? AND lon >= ? AND lon <= ? AND category IN ($catSql)"
                }
                try {
                    db.rawQuery(sql, arrayOf(b[0], b[1], b[2], b[3]).map { it.toString() }.toTypedArray()).use { c ->
                        while (c.moveToNext()) {
                            val cat = PoiCategory.of(c.getString(3)) ?: continue
                            out += Poi(c.getLong(0), c.getDouble(1), c.getDouble(2), cat, c.getString(4) ?: "", cc)
                        }
                    }
                } catch (_: Exception) { /* defektes Paket überspringen */ }
            }
            out.sortedBy { Geo.distance(lat, lon, it.lat, it.lon) }
        }

    /** Alle POIs einer Kategorie in einer Box (z. B. Tankstellen entlang einer Sektion). */
    suspend fun inBox(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, category: PoiCategory): List<Poi> =
        withContext(Dispatchers.IO) {
            val out = ArrayList<Poi>()
            for ((cc, db) in dbs()) try {
                db.rawQuery(
                    "SELECT id, lat, lon, category, name FROM poi WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? AND category = ?",
                    arrayOf(minLat.toString(), maxLat.toString(), minLon.toString(), maxLon.toString(), category.id),
                ).use { c ->
                    while (c.moveToNext()) out += Poi(c.getLong(0), c.getDouble(1), c.getDouble(2), category, c.getString(4) ?: "", cc)
                }
            } catch (_: Exception) { }
            out
        }

    /** Wege mit Belag-Angaben im Umkreis (R*Tree des POI-Pakets). */
    suspend fun surfaceWaysNear(lat: Double, lon: Double, radiusM: Double): List<SurfaceWay> = withContext(Dispatchers.IO) {
        val b = box(lat, lon, radiusM)
        val out = ArrayList<SurfaceWay>()
        for ((_, db) in dbs()) {
            if (!hasTable(db, "way_surface") || !hasTable(db, "way_rtree")) continue
            try {
                db.rawQuery(
                    "SELECT w.surface, w.tracktype, w.highway, w.coords FROM way_surface w JOIN way_rtree r ON r.id = w.id " +
                        "WHERE r.maxLat >= ? AND r.minLat <= ? AND r.maxLon >= ? AND r.minLon <= ?",
                    arrayOf(b[0].toString(), b[1].toString(), b[2].toString(), b[3].toString()),
                ).use { c ->
                    while (c.moveToNext()) {
                        out += SurfaceWay(c.getString(0), c.getString(1), c.getString(2), decodeCoords(c.getString(3)))
                    }
                }
            } catch (_: Exception) { }
        }
        out
    }

    fun hasSurfaceData(): Boolean = dbs().any { (_, db) ->
        hasTable(db, "way_surface") && hasTable(db, "way_rtree") &&
            runCatching { db.rawQuery("SELECT 1 FROM way_surface LIMIT 1", null).use { it.moveToFirst() } }.getOrDefault(false)
    }
    fun hasAnyPoiData(): Boolean = dbs().isNotEmpty()

    companion object {
        fun decodeCoords(s: String?): List<LatLon> =
            s.orEmpty().split(';').mapNotNull {
                val i = it.indexOf(',')
                if (i < 0) null else {
                    val la = it.substring(0, i).toDoubleOrNull(); val lo = it.substring(i + 1).toDoubleOrNull()
                    if (la != null && lo != null) LatLon(la, lo) else null
                }
            }
    }
}
