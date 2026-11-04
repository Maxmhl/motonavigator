package de.tetnavigator.core.database

import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import de.tetnavigator.core.common.Geo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * R*Tree-Index über alle Track-Segmente (Punkt idx → idx+1).
 * Schlüssel = sectionId shl 24 or idx (bis 16 Mio. Punkte pro Sektion).
 *
 * Fällt auf eine Bounding-Box-Abfrage über `track_point(lat)` zurück, falls die
 * SQLite-Version kein R*Tree-Modul hat.
 */
class SpatialIndex(private val db: TetDatabase) {

    private val sql get() = db.openHelper.writableDatabase

    val available: Boolean get() {
        sql // öffnet DB → onOpen → ensureTable
        return rtreeAvailable
    }

    suspend fun indexSection(sectionId: Long, points: List<TrackPointEntity>) = withContext(Dispatchers.IO) {
        if (!available || points.size < 2) return@withContext
        val w = sql
        w.beginTransaction()
        try {
            val stmt = w.compileStatement("INSERT OR REPLACE INTO $TABLE(id, minLat, maxLat, minLon, maxLon) VALUES (?,?,?,?,?)")
            for (i in 0 until points.size - 1) {
                val a = points[i]; val b = points[i + 1]
                stmt.bindLong(1, key(sectionId, a.idx))
                stmt.bindDouble(2, min(a.lat, b.lat))
                stmt.bindDouble(3, max(a.lat, b.lat))
                stmt.bindDouble(4, min(a.lon, b.lon))
                stmt.bindDouble(5, max(a.lon, b.lon))
                stmt.executeInsert()
                stmt.clearBindings()
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    suspend fun removeSection(sectionId: Long) = withContext(Dispatchers.IO) {
        if (!available) return@withContext
        sql.execSQL("DELETE FROM $TABLE WHERE id BETWEEN ? AND ?", arrayOf<Any>(key(sectionId, 0), key(sectionId, MAX_IDX)))
    }

    /** Alle Segmente, deren Box den Umkreis [radiusM] um (lat, lon) schneidet. */
    suspend fun segmentsNear(lat: Double, lon: Double, radiusM: Double): List<SegmentRef> = withContext(Dispatchers.IO) {
        val dLat = radiusM / Geo.METERS_PER_DEG_LAT
        val dLon = radiusM / (Geo.METERS_PER_DEG_LAT * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        if (available) {
            val out = ArrayList<SegmentRef>()
            sql.query(
                "SELECT id FROM $TABLE WHERE maxLat >= ? AND minLat <= ? AND maxLon >= ? AND minLon <= ?",
                arrayOf<Any>(lat - dLat, lat + dLat, lon - dLon, lon + dLon),
            ).use { c -> while (c.moveToNext()) out += unkey(c.getLong(0)) }
            out
        } else {
            db.trackDao().pointsInBox(lat - dLat, lat + dLat, lon - dLon, lon + dLon).map { SegmentRef(it.sectionId, it.idx) }
        }
    }

    /**
     * Nächster Trackpunkt (Projektion auf Segment) im Umkreis. Sucht mit
     * wachsendem Radius bis [maxRadiusM].
     */
    suspend fun nearest(lat: Double, lon: Double, maxRadiusM: Double = 50_000.0): NearestHit? {
        var r = 200.0
        while (r <= maxRadiusM * 2) {
            val refs = segmentsNear(lat, lon, r)
            if (refs.isNotEmpty()) {
                var best: NearestHit? = null
                val dao = db.trackDao()
                for (ref in refs.take(500)) {
                    val a = dao.point(ref.sectionId, ref.idx) ?: continue
                    val b = dao.point(ref.sectionId, ref.idx + 1) ?: a
                    val p = Geo.project(lat, lon, a.lat, a.lon, b.lat, b.lon)
                    if (best == null || p.distanceM < best.distanceM) {
                        val dist = a.distM + (b.distM - a.distM) * p.t
                        best = NearestHit(ref.sectionId, ref.idx, p.lat, p.lon, p.distanceM, dist)
                    }
                }
                if (best != null && best.distanceM <= r) return best
            }
            r *= 2.5
        }
        return null
    }

    data class NearestHit(
        val sectionId: Long,
        val idx: Int,
        val lat: Double,
        val lon: Double,
        val distanceM: Double,
        /** Distanz ab Sektionsstart am Projektionspunkt. */
        val alongM: Double,
    )

    companion object {
        const val TABLE = "segment_rtree"
        private const val MAX_IDX = (1 shl 24) - 1

        @Volatile private var rtreeAvailable = false

        fun key(sectionId: Long, idx: Int): Long = (sectionId shl 24) or idx.toLong()
        fun unkey(k: Long) = SegmentRef(k ushr 24, (k and MAX_IDX.toLong()).toInt())

        internal fun ensureTable(db: SupportSQLiteDatabase) {
            rtreeAvailable = try {
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS $TABLE USING rtree(id, minLat, maxLat, minLon, maxLon)")
                true
            } catch (e: Exception) {
                Log.w("SpatialIndex", "R*Tree nicht verfügbar, Fallback auf Box-Abfrage", e)
                false
            }
        }
    }
}
