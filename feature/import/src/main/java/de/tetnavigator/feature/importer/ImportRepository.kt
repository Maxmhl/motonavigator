package de.tetnavigator.feature.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import de.tetnavigator.core.common.Countries
import de.tetnavigator.core.common.Geo
import de.tetnavigator.core.common.GeoPoint
import de.tetnavigator.core.common.LatLon
import de.tetnavigator.core.database.CountryEntity
import de.tetnavigator.core.database.ProgressEntity
import de.tetnavigator.core.database.SectionEntity
import de.tetnavigator.core.database.SpatialIndex
import de.tetnavigator.core.database.TetDatabase
import de.tetnavigator.core.database.TrackFileEntity
import de.tetnavigator.core.database.TrackPointEntity
import de.tetnavigator.core.database.WaypointEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

data class SectionPreview(
    val name: String,
    val lengthM: Double,
    val ascentM: Double,
    val pointCount: Int,
    val changedRanges: List<Pair<Double, Double>>,
)

data class OlderVersion(val fileId: Long, val fileName: String, val fileDate: Long, val changedSegments: Int)

data class FilePreview(
    val entryName: String,
    val displayName: String,
    val sha256: String,
    val sections: List<SectionPreview>,
    val lengthM: Double,
    val waypointCount: Int,
    val detectedCountry: String?,
    val polyline: List<List<LatLon>>,
    val changedPolylines: List<List<LatLon>>,
    val fileDate: Long,
    val olderVersion: OlderVersion?,
    val alreadyImported: Boolean,
)

data class StagedImport(
    val id: String,
    val cacheFile: File,
    val sourceName: String,
    val isZip: Boolean,
    val files: List<FilePreview>,
) {
    val sectionCount get() = files.sumOf { it.sections.size }
    val lengthM get() = files.sumOf { it.lengthM }
    val waypointCount get() = files.sumOf { it.waypointCount }
    val changedSegments get() = files.sumOf { it.olderVersion?.changedSegments ?: 0 }
}

data class ImportResult(val files: Int, val sections: Int, val skippedDuplicates: Int, val fileIds: List<Long>)

/**
 * GPX-Import (Spezifikation 3 „Import“): Dateiauswahl / „Teilen mit…“ / ZIP.
 * Die Datei wird in den App-Cache kopiert, gestreamt analysiert (Vorschau) und
 * beim Bestätigen Track für Track in Room geschrieben. Nichts verlässt das Gerät.
 */
@Singleton
class ImportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: TetDatabase,
    private val spatial: SpatialIndex,
) {
    private val dao get() = db.trackDao()
    private val parser = GpxParser()
    private val diff = TrackDiff()

    private val cacheDir get() = File(context.cacheDir, "import").apply { mkdirs() }

    suspend fun stage(uri: Uri): StagedImport = withContext(Dispatchers.IO) {
        val name = displayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "import.gpx"
        val target = File(cacheDir, UUID.randomUUID().toString())
        context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
            ?: throw GpxParseException("Datei kann nicht gelesen werden")
        stageFile(target, name)
    }

    suspend fun stageFile(file: File, sourceName: String): StagedImport = withContext(Dispatchers.IO) {
        val zip = isZip(file)
        val previews = ArrayList<FilePreview>()
        forEachGpx(file, zip, sourceName) { entryName, entryTime, stream ->
            previews += preview(entryName, entryTime, stream)
        }
        if (previews.isEmpty()) throw GpxParseException(if (zip) "Keine GPX-Dateien im ZIP gefunden" else "Keine Tracks in der Datei")
        StagedImport(file.name, file, sourceName, zip, previews)
    }

    fun discard(staged: StagedImport) { staged.cacheFile.delete() }

    private suspend fun preview(entryName: String, entryTime: Long?, input: InputStream): FilePreview {
        val md = MessageDigest.getInstance("SHA-256")
        val digesting = DigestInputStream(input, md)
        val stats = PreviewCollector()
        parser.parse(digesting, stats)
        drain(digesting)
        val sha = md.digest().toHex()

        val existingSame = dao.fileBySha(sha)
        val older = findOlderVersion(entryName)
        val oldSections = older?.let { loadSections(it.id) }.orEmpty()

        val sections = ArrayList<SectionPreview>()
        val changedLines = ArrayList<List<LatLon>>()
        var changedSegments = 0
        for (t in stats.tracks) {
            val pts = t.points
            val ranges = if (older != null) {
                val old = matchOld(t.name, pts, oldSections)
                val r = diff.compare(old ?: emptyList(), pts)
                changedSegments += r.changedCount
                if (r.ranges.isNotEmpty()) changedLines += runs(pts, r.changed).map { run -> downsample(run, 30.0) }
                r.ranges
            } else emptyList()
            sections += SectionPreview(t.name, Geo.polylineLength(pts), Geo.ascent(pts.map { it.ele }), pts.size, ranges)
        }

        val detected = Countries.detectFromText(entryName)
            ?: stats.meta?.name?.let(Countries::detectFromText)
            ?: stats.meta?.desc?.let(Countries::detectFromText)
            ?: Countries.detectFromPoints(stats.tracks.asSequence().flatMap { it.preview.asSequence() })

        return FilePreview(
            entryName = entryName,
            displayName = entryName.substringAfterLast('/'),
            sha256 = sha,
            sections = sections,
            lengthM = sections.sumOf { it.lengthM },
            waypointCount = stats.waypoints.size,
            detectedCountry = detected?.code,
            polyline = stats.tracks.map { it.preview },
            changedPolylines = changedLines,
            fileDate = stats.meta?.time ?: stats.firstTime ?: entryTime ?: System.currentTimeMillis(),
            olderVersion = older?.let { OlderVersion(it.id, it.fileName, it.fileDate, changedSegments) },
            alreadyImported = existingSame != null,
        )
    }

    /**
     * Schreibt die Vorschau in die Datenbank. Vorversionen werden ersetzt,
     * der Fortschritt wird auf die neue Geometrie übertragen.
     */
    suspend fun commit(
        staged: StagedImport,
        countryOverride: Map<String, String> = emptyMap(),
        onProgress: (String) -> Unit = {},
    ): ImportResult = withContext(Dispatchers.IO) {
        var files = 0; var sectionsTotal = 0; var skipped = 0
        val ids = ArrayList<Long>()
        val byEntry = staged.files.associateBy { it.entryName }
        forEachGpx(staged.cacheFile, staged.isZip, staged.sourceName) { entryName, _, stream ->
            val pv = byEntry[entryName] ?: return@forEachGpx
            if (pv.alreadyImported) { skipped++; return@forEachGpx }
            val cc = (countryOverride[entryName] ?: pv.detectedCountry ?: "XX").uppercase()
            onProgress(pv.displayName)
            val (fileId, n) = writeFile(pv, cc, stream)
            ids += fileId; files++; sectionsTotal += n
        }
        staged.cacheFile.delete()
        ImportResult(files, sectionsTotal, skipped, ids)
    }

    private suspend fun writeFile(pv: FilePreview, cc: String, input: InputStream): Pair<Long, Int> {
        dao.upsertCountry(CountryEntity(cc, Countries.name(cc)))
        val older = pv.olderVersion
        val oldSections = older?.let { loadSections(it.fileId) }.orEmpty()
        val oldProgress = older?.let { o -> dao.sectionsOfFile(o.fileId).associate { it.name to db.progressDao().get(it.id) } }.orEmpty()

        val fileId = dao.insertFile(
            TrackFileEntity(
                countryCode = cc, fileName = pv.displayName, importedAt = System.currentTimeMillis(),
                fileDate = pv.fileDate, sha256 = pv.sha256, changedSegments = older?.changedSegments ?: 0,
                previousFileDate = older?.fileDate,
            ),
        )
        val waypoints = ArrayList<GpxWaypoint>()
        val newSections = ArrayList<Pair<Long, List<GeoPoint>>>()
        var order = 0
        // Tracks werden einzeln gelesen und sofort geschrieben (Streaming).
        val reader = parser.reader(input)
        while (true) {
            when (val item = reader.next() ?: break) {
                is GpxItem.Track -> newSections += writeSection(fileId, cc, order++, item.track, oldSections, oldProgress)
                is GpxItem.Waypoint -> waypoints += item.wpt
                is GpxItem.Meta -> Unit
            }
        }

        // Wegpunkte der nächstgelegenen Sektion zuordnen.
        if (waypoints.isNotEmpty()) {
            val grids = newSections.map { (id, pts) -> id to SegmentGrid(pts, 500.0) }
            dao.insertWaypoints(
                waypoints.map { w ->
                    val gp = GeoPoint(w.lat, w.lon)
                    val best = grids.minByOrNull { it.second.nearest(gp, rings = 2)?.second?.distanceM ?: Double.MAX_VALUE }
                    val ok = best?.second?.nearest(gp, rings = 2)?.second?.distanceM?.let { it < 2000 } == true
                    WaypointEntity(trackFileId = fileId, sectionId = if (ok) best?.first else null, lat = w.lat, lon = w.lon, name = w.name, type = w.type)
                },
            )
        }

        if (older != null) deleteFile(older.fileId)
        return fileId to newSections.size
    }

    private suspend fun writeSection(
        fileId: Long,
        cc: String,
        order: Int,
        t: GpxTrack,
        oldSections: Map<String, List<GeoPoint>>,
        oldProgress: Map<String, ProgressEntity?>,
    ): Pair<Long, List<GeoPoint>> {
        val pts = t.points
        val cum = Geo.cumulative(pts)
        val oldMatchName = matchOldName(t.name, pts, oldSections)
        val d = oldMatchName?.let { diff.compare(oldSections.getValue(it), pts) }
        val bbox = de.tetnavigator.core.common.BoundingBox.of(pts)!!
        var maxEle: Double? = null; var maxEleDist: Double? = null
        pts.forEachIndexed { i, p -> if (p.ele != null && (maxEle == null || p.ele!! > maxEle!!)) { maxEle = p.ele; maxEleDist = cum[i] } }

        val sectionId = db.withTransaction {
            val id = dao.insertSection(
                SectionEntity(
                    trackFileId = fileId, countryCode = cc, name = t.name, lengthM = cum.last(),
                    ascentM = Geo.ascent(pts.map { it.ele }), descentM = Geo.descent(pts.map { it.ele }),
                    orderIndex = order, pointCount = pts.size,
                    minLat = bbox.minLat, minLon = bbox.minLon, maxLat = bbox.maxLat, maxLon = bbox.maxLon,
                    maxEle = maxEle, maxEleDistM = maxEleDist,
                    changedRanges = d?.let { TrackDiff.encodeRanges(it.ranges) } ?: "",
                ),
            )
            pts.indices.chunked(2000).forEach { chunk ->
                dao.insertPoints(chunk.map { i -> TrackPointEntity(id, i, pts[i].lat, pts[i].lon, pts[i].ele, cum[i], changed = d?.changed?.get(i) == true) })
            }
            id
        }
        spatial.indexSection(sectionId, dao.points(sectionId))

        // Fortschritt übertragen
        if (oldMatchName != null) {
            val prog = oldProgress[oldMatchName]
            if (prog != null) {
                val newDist = transferDistance(oldSections.getValue(oldMatchName), prog.rideDistanceM, pts, cum)
                db.progressDao().upsert(ProgressEntity(sectionId, prog.ridden, if (prog.ridden) cum.last() else newDist, System.currentTimeMillis()))
            }
        }
        return sectionId to pts
    }

    /** Punkt bei [oldDist] auf alter Linie → Distanz auf neuer Linie. */
    private fun transferDistance(old: List<GeoPoint>, oldDist: Double, new: List<GeoPoint>, newCum: DoubleArray): Double {
        if (oldDist <= 0) return 0.0
        val oc = Geo.cumulative(old)
        var i = oc.indexOfFirst { it >= oldDist }.let { if (it < 0) old.size - 1 else it }
        if (i == 0) i = 1
        val f = ((oldDist - oc[i - 1]) / (oc[i] - oc[i - 1]).coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
        val p = GeoPoint(old[i - 1].lat + (old[i].lat - old[i - 1].lat) * f, old[i - 1].lon + (old[i].lon - old[i - 1].lon) * f)
        val hit = SegmentGrid(new, 300.0).nearest(p, rings = 3) ?: return 0.0
        val (idx, pr) = hit
        return newCum[idx] + (newCum[idx + 1] - newCum[idx]) * pr.t
    }

    suspend fun deleteFile(fileId: Long) = withContext(Dispatchers.IO) {
        for (s in dao.sectionsOfFile(fileId)) {
            spatial.removeSection(s.id)
            db.progressDao().delete(s.id)
        }
        dao.deleteFile(fileId)
    }

    // --- Hilfsfunktionen -----------------------------------------------------

    private suspend fun findOlderVersion(entryName: String): TrackFileEntity? {
        val stem = FileNames.stem(entryName)
        if (stem.isEmpty()) return null
        return dao.allFiles().firstOrNull { FileNames.stem(it.fileName) == stem }
    }

    private suspend fun loadSections(fileId: Long): Map<String, List<GeoPoint>> =
        dao.sectionsOfFile(fileId).associate { s -> s.name to dao.points(s.id).map { GeoPoint(it.lat, it.lon, it.ele) } }

    private fun matchOldName(name: String, pts: List<GeoPoint>, old: Map<String, List<GeoPoint>>): String? {
        if (old.containsKey(name)) return name
        val start = pts.first()
        return old.entries.minByOrNull { Geo.distance(it.value.first(), start) }
            ?.takeIf { Geo.distance(it.value.first(), start) < 500 }?.key
    }

    private fun matchOld(name: String, pts: List<GeoPoint>, old: Map<String, List<GeoPoint>>) =
        matchOldName(name, pts, old)?.let(old::get)

    private fun runs(pts: List<GeoPoint>, flags: BooleanArray): List<List<GeoPoint>> {
        val out = ArrayList<List<GeoPoint>>()
        var cur = ArrayList<GeoPoint>()
        for (i in pts.indices) {
            if (flags[i]) cur += pts[i] else if (cur.isNotEmpty()) { if (cur.size > 1) out += cur; cur = ArrayList() }
        }
        if (cur.size > 1) out += cur
        return out
    }

    private fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) { null }

    private class PreviewCollector : GpxListener {
        class T(val name: String, val points: List<GeoPoint>, val preview: List<LatLon>)
        var meta: GpxMetadata? = null
        val tracks = ArrayList<T>()
        val waypoints = ArrayList<GpxWaypoint>()
        var firstTime: Long? = null
        override fun onMetadata(meta: GpxMetadata) { this.meta = (this.meta ?: meta).let { m -> GpxMetadata(m.name ?: meta.name, m.desc ?: meta.desc, m.time ?: meta.time) } }
        override fun onTrack(track: GpxTrack) {
            if (firstTime == null) firstTime = track.points.firstOrNull { it.time != null }?.time
            tracks += T(track.name, track.points, downsample(track.points, 150.0))
        }
        override fun onWaypoint(wpt: GpxWaypoint) { waypoints += wpt }
    }

    companion object {
        fun isZip(file: File): Boolean = file.inputStream().use { s ->
            val b = ByteArray(4); s.read(b) == 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() && b[2].toInt() == 3 && b[3].toInt() == 4
        }

        /** Ruft [block] für jede GPX-Datei (direkt oder im ZIP) mit eigenem Stream auf. */
        suspend fun forEachGpx(file: File, zip: Boolean, sourceName: String, block: suspend (String, Long?, InputStream) -> Unit) {
            if (!zip) {
                BufferedInputStream(file.inputStream()).use { block(sourceName, null, it) }
                return
            }
            ZipInputStream(BufferedInputStream(file.inputStream())).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val n = e.name
                    if (e.isDirectory || !n.endsWith(".gpx", ignoreCase = true) || n.contains("__MACOSX") || n.substringAfterLast('/').startsWith(".")) continue
                    block(n, e.time.takeIf { it > 0 }, NonClosing(z))
                    z.closeEntry()
                }
            }
        }

        fun downsample(pts: List<GeoPoint>, stepM: Double): List<LatLon> {
            if (pts.isEmpty()) return emptyList()
            val out = ArrayList<LatLon>()
            var last = pts.first(); out += last.toLatLon()
            for (p in pts) if (Geo.distance(last, p) >= stepM) { out += p.toLatLon(); last = p }
            if (out.last() != pts.last().toLatLon()) out += pts.last().toLatLon()
            return out
        }

        private fun drain(s: InputStream) { val b = ByteArray(16 * 1024); while (s.read(b) >= 0) Unit }
        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }

    private class NonClosing(s: InputStream) : FilterInputStream(s) { override fun close() {} }
}

