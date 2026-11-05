package de.tetnavigator.data.download

import de.tetnavigator.core.offlinedata.PmTiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile

/** Ergebnis der Planung: welche Kacheln aus welchem Build. */
class ExtractPlan(
    val build: String,
    val url: String,
    val header: PmTiles.Header,
    val metadataJson: String,
    /** Kacheln in ID-Reihenfolge: ID und Index des Daten-Blobs. */
    val tileIds: LongArray,
    val tileBlob: IntArray,
    /** Eindeutige Quell-Blobs, nach Quell-Offset sortiert. */
    val blobOffset: LongArray,
    val blobLength: IntArray,
    val directoryBytes: Long,
) {
    val tileBytes: Long get() = blobLength.sumOf { it.toLong() }
    val tileCount: Int get() = tileIds.size
}

/**
 * Zieht einen Länder-Ausschnitt per HTTP-Range aus dem Protomaps-Planet-Archiv
 * (wie `pmtiles extract`), direkt auf dem Gerät. Fortsetzbar; jede Kachel wird
 * über die gzip-Prüfsumme (CRC32) verifiziert.
 */
class ProtomapsExtractor(private val client: OkHttpClient) {

    suspend fun latestBuild(): String = withContext(Dispatchers.IO) {
        val body = get(DirectSources.PROTOMAPS_BUILDS).decodeToString()
        val builds = Json.parseToJsonElement(body).jsonArray.map { it.jsonObject["key"]!!.jsonPrimitive.content }
            .filter { it.endsWith(".pmtiles") }
        builds.maxOrNull() ?: throw DownloadException("Keine Protomaps-Builds gefunden")
    }

    /** @param mask sortierte Kachel-IDs (Landesmaske bis zur gewünschten Zoomstufe) */
    suspend fun plan(build: String, mask: LongArray): ExtractPlan = withContext(Dispatchers.IO) {
        val url = DirectSources.PROTOMAPS_BASE + build
        var dirBytes = 0L
        fun range(off: Long, len: Int): ByteArray { dirBytes += len; return getRange(url, off, len) }

        val header = PmTiles.Header.parse(range(0, PmTiles.HEADER_SIZE))
        require(header.tileType == PmTiles.TYPE_MVT) { "Unerwarteter Kacheltyp" }
        val meta = range(header.metadataOffset, header.metadataLength.toInt()).let {
            if (header.internalCompression == PmTiles.COMPRESSION_GZIP) PmTiles.gunzip(it) else it
        }.decodeToString()

        val tileIds = ArrayList<Long>(mask.size)
        val tileSrc = ArrayList<Long>(mask.size)
        val tileLen = ArrayList<Int>(mask.size)

        fun firstAtLeast(v: Long): Int {
            var lo = 0; var hi = mask.size
            while (lo < hi) { val m = (lo + hi) ushr 1; if (mask[m] < v) lo = m + 1 else hi = m }
            return lo
        }

        suspend fun walk(off: Long, len: Long, depth: Int) {
            currentCoroutineContext().ensureActive()
            if (depth > 4) return
            val entries = PmTiles.deserializeDirectory(range(off, len.toInt()), header.internalCompression)
            for ((i, e) in entries.withIndex()) {
                if (e.runLength == 0) {
                    val end = if (i + 1 < entries.size) entries[i + 1].tileId else Long.MAX_VALUE
                    val k = firstAtLeast(e.tileId)
                    if (k < mask.size && mask[k] < end) walk(header.leafOffset + e.offset, e.length.toLong(), depth + 1)
                } else {
                    var k = firstAtLeast(e.tileId)
                    while (k < mask.size && mask[k] < e.tileId + e.runLength) {
                        tileIds += mask[k]; tileSrc += e.offset; tileLen += e.length
                        k++
                    }
                }
            }
        }
        walk(header.rootOffset, header.rootLength, 0)

        // Reihenfolge nach Kachel-ID (Blätter werden nicht zwingend sortiert besucht)
        val order = tileIds.indices.sortedBy { tileIds[it] }
        val ids = LongArray(order.size) { tileIds[order[it]] }
        val srcOf = LongArray(order.size) { tileSrc[order[it]] }
        val lenOf = IntArray(order.size) { tileLen[order[it]] }
        // eindeutige Blobs nach Offset
        val uniq = srcOf.indices.distinctBy { srcOf[it] }.sortedBy { srcOf[it] }
        val blobOffset = LongArray(uniq.size) { srcOf[uniq[it]] }
        val blobLength = IntArray(uniq.size) { lenOf[uniq[it]] }
        val blobIndex = HashMap<Long, Int>(uniq.size * 2).apply { blobOffset.forEachIndexed { i, o -> put(o, i) } }
        val tileBlob = IntArray(ids.size) { blobIndex.getValue(srcOf[it]) }
        ExtractPlan(build, url, header, meta, ids, tileBlob, blobOffset, blobLength, dirBytes)
    }

    /**
     * Lädt alle Kacheln des Plans und schreibt das Archiv nach [target].
     * Zwischenstand liegt in [workDir] – ein erneuter Aufruf setzt fort.
     */
    suspend fun extract(plan: ExtractPlan, target: File, workDir: File, onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        workDir.mkdirs()
        val dataFile = File(workDir, "tiles.data")
        val stateFile = File(workDir, "state.txt")
        val total = plan.tileBytes
        val batches = batches(plan)
        val key = "${plan.build}|${plan.tileCount}|${plan.blobOffset.size}|$total"

        var startBatch = 0
        var written = 0L
        stateFile.takeIf { it.exists() }?.readLines()?.let { l ->
            if (l.size == 3 && l[0] == key) { startBatch = l[1].toInt(); written = l[2].toLong() }
        }
        val destOffset = LongArray(plan.blobOffset.size)
        run { // Ziel-Offsets sind deterministisch (Blobs in Offset-Reihenfolge hintereinander)
            var o = 0L
            for (i in plan.blobOffset.indices) { destOffset[i] = o; o += plan.blobLength[i] }
        }
        RandomAccessFile(dataFile, "rw").use { raf ->
            raf.setLength(written)
            raf.seek(written)
            onProgress(written, total)
            for (b in startBatch until batches.size) {
                currentCoroutineContext().ensureActive()
                val (first, last) = batches[b]
                val start = plan.blobOffset[first]
                val end = plan.blobOffset[last] + plan.blobLength[last]
                val bytes = getRange(plan.url, plan.header.dataOffset + start, (end - start).toInt())
                for (i in first..last) {
                    val rel = (plan.blobOffset[i] - start).toInt()
                    val tile = bytes.copyOfRange(rel, rel + plan.blobLength[i])
                    if (plan.header.tileCompression == PmTiles.COMPRESSION_GZIP && !PmTiles.gzipValid(tile)) {
                        throw DownloadException("Prüfsumme einer Kartenkachel falsch – Download wird wiederholt")
                    }
                    raf.write(tile)
                }
                written = raf.filePointer
                stateFile.writeText("$key\n${b + 1}\n$written")
                onProgress(written, total)
            }
        }

        // Verzeichnis: Kacheln in ID-Reihenfolge, gleiche Blobs als Lauf zusammenfassen
        val entries = ArrayList<PmTiles.Entry>()
        for (i in plan.tileIds.indices) {
            val blob = plan.tileBlob[i]
            val off = destOffset[blob]; val len = plan.blobLength[blob]
            val prev = entries.lastOrNull()
            if (prev != null && prev.offset == off && prev.tileId + prev.runLength == plan.tileIds[i]) {
                entries[entries.lastIndex] = prev.copy(runLength = prev.runLength + 1)
            } else {
                entries += PmTiles.Entry(plan.tileIds[i], off, len, 1)
            }
        }
        if (plan.tileIds.isEmpty()) throw DownloadException("Keine Kartenkacheln für dieses Land gefunden")
        val h = plan.header
        val maxZ = de.tetnavigator.core.offlinedata.TileMath.zoomOf(plan.tileIds.last())
        PmTiles.assemble(
            target, entries, dataFile, plan.metadataJson, plan.tileIds.size.toLong(), plan.blobOffset.size.toLong(),
            h.tileType, h.tileCompression, 0, maxZ, doubleArrayOf(h.minLon, h.minLat, h.maxLon, h.maxLat), minOf(h.centerZoom, maxZ),
        )
        workDir.deleteRecursively()
    }

    /** Blobs zu Range-Requests bündeln (Lücke ≤ 256 kB, ≤ 4 MB je Request). */
    private fun batches(plan: ExtractPlan): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var first = 0
        for (i in 1..plan.blobOffset.size) {
            if (i == plan.blobOffset.size) { out += first to (i - 1); break }
            val gap = plan.blobOffset[i] - (plan.blobOffset[i - 1] + plan.blobLength[i - 1])
            val size = plan.blobOffset[i] + plan.blobLength[i] - plan.blobOffset[first]
            if (gap > 256 * 1024 || size > 4 * 1024 * 1024) { out += first to (i - 1); first = i }
        }
        return out
    }

    private fun get(url: String): ByteArray =
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw DownloadException("HTTP ${r.code}: $url")
            r.body?.bytes() ?: throw DownloadException("Leere Antwort: $url")
        }

    private fun getRange(url: String, off: Long, len: Int): ByteArray {
        repeat(3) { attempt ->
            try {
                client.newCall(Request.Builder().url(url).header("Range", "bytes=$off-${off + len - 1}").build()).execute().use { r ->
                    if (r.code != 206) throw DownloadException("Server unterstützt keine Teil-Downloads (HTTP ${r.code})")
                    val b = r.body?.bytes() ?: throw DownloadException("Leere Antwort")
                    if (b.size != len) throw DownloadException("Unvollständige Antwort (${b.size}/$len Bytes)")
                    return b
                }
            } catch (e: OfflineLockedException) {
                throw e
            } catch (e: Exception) {
                if (attempt == 2) throw e
                Thread.sleep(1000L shl attempt)
            }
        }
        error("unreachable")
    }
}
