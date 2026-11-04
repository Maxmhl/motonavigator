package de.tetnavigator.feature.logistik

import de.tetnavigator.core.common.Geo
import de.tetnavigator.core.common.GeoPoint
import de.tetnavigator.core.database.TetDatabase
import de.tetnavigator.core.offlinedata.Poi
import de.tetnavigator.core.offlinedata.PoiCategory
import de.tetnavigator.core.offlinedata.PoiDatabase
import de.tetnavigator.core.preferences.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class PoiHit(val poi: Poi, val distanceM: Double, val bearing: Double)

/** Checks vor dem Start (Sektions-Details). */
data class SectionChecks(
    val fuel: CheckLevel,
    val fuelText: String,
    val light: CheckLevel,
    val lightText: String,
    val hazards: Int,
    val hazardText: String,
)

@Singleton
class LogistikRepository @Inject constructor(
    private val db: TetDatabase,
    private val poi: PoiDatabase,
    private val settings: SettingsRepository,
) {
    /** Camping & Versorgung im Umkreis. */
    suspend fun poisNear(lat: Double, lon: Double, radiusM: Double, cats: Set<PoiCategory>): List<PoiHit> =
        poi.near(lat, lon, radiusM, cats).map { PoiHit(it, Geo.distance(lat, lon, it.lat, it.lon), Geo.bearing(lat, lon, it.lat, it.lon)) }

    val hasPoiData: Boolean get() = poi.hasAnyPoiData()

    /** Tankstellen bis [maxOffsetM] neben der Linie, projiziert auf die Distanz entlang. */
    suspend fun stationsAlong(points: List<GeoPoint>, cum: DoubleArray, maxOffsetM: Double = 1_500.0): List<StationOnTrack> =
        withContext(Dispatchers.Default) {
            if (points.size < 2) return@withContext emptyList()
            val box = de.tetnavigator.core.common.BoundingBox.of(points)!!.expand(maxOffsetM)
            val stations = poi.inBox(box.minLat, box.minLon, box.maxLat, box.maxLon, PoiCategory.FUEL)
            val step = (points.size / 2000).coerceAtLeast(1) // grob, für lange Sektionen
            stations.mapNotNull { s ->
                var best = Double.MAX_VALUE; var bestAlong = 0.0
                var i = 0
                while (i < points.size - 1) {
                    val j = (i + step).coerceAtMost(points.size - 1)
                    val a = points[i]; val b = points[j]
                    val p = Geo.project(s.lat, s.lon, a.lat, a.lon, b.lat, b.lon)
                    if (p.distanceM < best) { best = p.distanceM; bestAlong = cum[i] + (cum[j] - cum[i]) * p.t }
                    i = j
                }
                if (best <= maxOffsetM) StationOnTrack(bestAlong, s.name.ifBlank { "Tankstelle" }, best) else null
            }.sortedBy { it.alongM }
        }

    suspend fun averageKmh(): Pair<Double, Boolean> {
        val t = db.rideDao().totals()
        return DaylightCalculator.averageKmh(t.offroadM, t.offroadMovingMs, settings.current().fallbackOffroadKmh)
    }

    suspend fun daylight(lat: Double, lon: Double, now: Instant = Instant.now()): DaylightInfo {
        val (v, measured) = averageKmh()
        return DaylightCalculator.compute(now, lat, lon, v, measured)
    }

    suspend fun sectionChecks(sectionId: Long, reversed: Boolean, here: GeoPoint?): SectionChecks = withContext(Dispatchers.Default) {
        val dao = db.trackDao()
        val s = dao.section(sectionId) ?: return@withContext SectionChecks(CheckLevel.WARN, "–", CheckLevel.WARN, "–", 0, "–")
        var pts = dao.points(sectionId).map { GeoPoint(it.lat, it.lon) }
        if (reversed) pts = pts.reversed()
        val cum = Geo.cumulative(pts)
        val cfg = settings.current()
        val range = FuelRange.rangeM(cfg.fuelRemainingL, cfg.consumptionLPer100)
        val full = FuelRange.rangeM(cfg.tankLiters, cfg.consumptionLPer100)

        val stations = if (poi.hasAnyPoiData()) stationsAlong(pts, cum) else emptyList()
        val fuel = FuelRange.check(s.lengthM, range, full, stations)
        val nearStart = if (poi.hasAnyPoiData()) poi.near(pts.first().lat, pts.first().lon, 50_000.0, setOf(PoiCategory.FUEL)).firstOrNull() else null
        val fuelText = buildString {
            append(if (fuel == CheckLevel.OK) "reicht" else "knapp")
            append(" – ${(range / 1000).toInt()} km Reichweite")
            if (nearStart != null) append(", letzte Tankstelle ${(Geo.distance(pts.first().lat, pts.first().lon, nearStart.lat, nearStart.lon) / 1000).toInt()} km vor Start")
            if (stations.isNotEmpty()) append(", ${stations.size} unterwegs")
            if (!poi.hasAnyPoiData()) append(" (keine POI-Daten)")
        }

        val ref = here ?: pts.first()
        val (avg, _) = averageKmh()
        val dl = DaylightCalculator.compute(Instant.now(), ref.lat, ref.lon, avg, false)
        val light = DaylightCalculator.check(s.lengthM / 1000, avg, dl.millisUntilDark)
        val driveMs = (s.lengthM / 1000 / avg * 3_600_000).toLong()
        val lightText = if (dl.polarDay) "Mitternachtssonne – ca. ${de.tetnavigator.core.common.Format.hm(driveMs)} h Fahrzeit"
        else "ca. ${de.tetnavigator.core.common.Format.hm(driveMs)} h Fahrzeit, ${de.tetnavigator.core.common.Format.hm(dl.millisUntilDark)} h bis dunkel"

        val b = de.tetnavigator.core.common.BoundingBox(s.minLat, s.minLon, s.maxLat, s.maxLon).expand(100.0)
        val pins = db.hazardDao().inBox(b.minLat, b.maxLat, b.minLon, b.maxLon).filter { h ->
            pts.windowed(2, 1).any { (a, c) -> Geo.project(h.lat, h.lon, a.lat, a.lon, c.lat, c.lon).distanceM < 100 }
        }
        val byType = pins.groupingBy { it.type }.eachCount()
        val hazardText = if (pins.isEmpty()) "keine markiert" else byType.entries.joinToString(", ") { "${it.value} × ${it.key.lowercase().replaceFirstChar(Char::uppercase)}" } + " markiert"
        SectionChecks(fuel, fuelText, light, lightText, pins.size, hazardText)
    }
}
