package de.tetnavigator.core.offlinedata

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * PMTiles v3 – Lesen und Schreiben (https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md).
 * Reines Kotlin, keine Netzwerkabhängigkeit.
 */
object PmTiles {
    const val HEADER_SIZE = 127
    const val MAX_ROOT = 16_384 - HEADER_SIZE

    const val COMPRESSION_NONE = 1
    const val COMPRESSION_GZIP = 2
    const val TYPE_MVT = 1
    const val TYPE_PNG = 2

    data class Header(
        val rootOffset: Long, val rootLength: Long,
        val metadataOffset: Long, val metadataLength: Long,
        val leafOffset: Long, val leafLength: Long,
        val dataOffset: Long, val dataLength: Long,
        val addressedTiles: Long, val tileEntries: Long, val tileContents: Long,
        val clustered: Boolean, val internalCompression: Int, val tileCompression: Int, val tileType: Int,
        val minZoom: Int, val maxZoom: Int,
        val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double,
        val centerZoom: Int, val centerLon: Double, val centerLat: Double,
    ) {
        fun toBytes(): ByteArray {
            val b = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            b.put("PMTiles".toByteArray()); b.put(3)
            for (v in listOf(rootOffset, rootLength, metadataOffset, metadataLength, leafOffset, leafLength, dataOffset, dataLength, addressedTiles, tileEntries, tileContents)) b.putLong(v)
            b.put(if (clustered) 1 else 0); b.put(internalCompression.toByte()); b.put(tileCompression.toByte()); b.put(tileType.toByte())
            b.put(minZoom.toByte()); b.put(maxZoom.toByte())
            b.putInt(e7(minLon)); b.putInt(e7(minLat)); b.putInt(e7(maxLon)); b.putInt(e7(maxLat))
            b.put(centerZoom.toByte()); b.putInt(e7(centerLon)); b.putInt(e7(centerLat))
            return b.array()
        }

        companion object {
            private fun e7(v: Double) = Math.round(v * 1e7).toInt()

            fun parse(bytes: ByteArray): Header {
                require(bytes.size >= HEADER_SIZE && String(bytes, 0, 7) == "PMTiles") { "Keine PMTiles-Datei" }
                require(bytes[7].toInt() == 3) { "Nur PMTiles v3 wird unterstützt" }
                val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                b.position(8)
                val l = LongArray(11) { b.long }
                val clustered = b.get().toInt() == 1
                val ic = b.get().toInt(); val tc = b.get().toInt(); val tt = b.get().toInt()
                val minZ = b.get().toInt() and 0xff; val maxZ = b.get().toInt() and 0xff
                val mnLon = b.int / 1e7; val mnLat = b.int / 1e7; val mxLon = b.int / 1e7; val mxLat = b.int / 1e7
                val cz = b.get().toInt() and 0xff; val cLon = b.int / 1e7; val cLat = b.int / 1e7
                return Header(l[0], l[1], l[2], l[3], l[4], l[5], l[6], l[7], l[8], l[9], l[10], clustered, ic, tc, tt, minZ, maxZ, mnLon, mnLat, mxLon, mxLat, cz, cLon, cLat)
            }
        }
    }

    /** Verzeichniseintrag: runLength 0 = Verweis auf Blatt-Verzeichnis. */
    data class Entry(val tileId: Long, val offset: Long, val length: Int, val runLength: Int)

    // --- Verzeichnisse ---------------------------------------------------

    fun serializeDirectory(entries: List<Entry>): ByteArray {
        val out = ByteArrayOutputStream()
        fun varint(v: Long) {
            var x = v
            while (x >= 0x80) { out.write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }
            out.write(x.toInt())
        }
        varint(entries.size.toLong())
        var last = 0L
        for (e in entries) { varint(e.tileId - last); last = e.tileId }
        for (e in entries) varint(e.runLength.toLong())
        for (e in entries) varint(e.length.toLong())
        for ((i, e) in entries.withIndex()) {
            if (i > 0 && e.offset == entries[i - 1].offset + entries[i - 1].length) varint(0) else varint(e.offset + 1)
        }
        return gzip(out.toByteArray())
    }

    fun deserializeDirectory(compressed: ByteArray, compression: Int = COMPRESSION_GZIP): List<Entry> {
        val raw = if (compression == COMPRESSION_GZIP) gunzip(compressed) else compressed
        var pos = 0
        fun varint(): Long {
            var shift = 0; var result = 0L
            while (true) {
                val b = raw[pos++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b < 0x80) return result
                shift += 7
            }
        }
        val n = varint().toInt()
        val ids = LongArray(n); var last = 0L
        for (i in 0 until n) { last += varint(); ids[i] = last }
        val runs = IntArray(n) { varint().toInt() }
        val lens = IntArray(n) { varint().toInt() }
        val offs = LongArray(n)
        for (i in 0 until n) {
            val v = varint()
            offs[i] = if (v == 0L && i > 0) offs[i - 1] + lens[i - 1] else v - 1
        }
        return List(n) { Entry(ids[it], offs[it], lens[it], runs[it]) }
    }

    /** Eintrag für [tileId] in einem Verzeichnis (binäre Suche). */
    fun find(entries: List<Entry>, tileId: Long): Entry? {
        var lo = 0; var hi = entries.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val e = entries[mid]
            when {
                tileId < e.tileId -> hi = mid - 1
                tileId > e.tileId -> lo = mid + 1
                else -> return e
            }
        }
        if (hi >= 0) {
            val e = entries[hi]
            if (e.runLength == 0) return e
            if (tileId - e.tileId < e.runLength) return e
        }
        return null
    }

    /**
     * Schreibt Header + Verzeichnisse + Metadaten + vorhandene Kacheldaten ([dataFile])
     * als fertiges Archiv nach [target].
     */
    fun assemble(
        target: File, entries: List<Entry>, dataFile: File, metadataJson: String,
        addressed: Long, contents: Long, tileType: Int, tileCompression: Int,
        minZoom: Int, maxZoom: Int, bounds: DoubleArray, centerZoom: Int,
    ) {
        val (root, leaves) = buildDirectories(entries)
        val meta = gzip(metadataJson.toByteArray())
        val rootOff = HEADER_SIZE.toLong()
        val metaOff = rootOff + root.size
        val leafOff = metaOff + meta.size
        val dataOff = leafOff + leaves.size
        val header = Header(
            rootOff, root.size.toLong(), metaOff, meta.size.toLong(), leafOff, leaves.size.toLong(), dataOff, dataFile.length(),
            addressed, entries.size.toLong(), contents,
            clustered = false, internalCompression = COMPRESSION_GZIP, tileCompression = tileCompression, tileType = tileType,
            minZoom = minZoom, maxZoom = maxZoom, minLon = bounds[0], minLat = bounds[1], maxLon = bounds[2], maxLat = bounds[3],
            centerZoom = centerZoom, centerLon = (bounds[0] + bounds[2]) / 2, centerLat = (bounds[1] + bounds[3]) / 2,
        )
        target.parentFile?.mkdirs()
        val part = File(target.path + ".tmp")
        part.outputStream().buffered(1 shl 20).use { out ->
            out.write(header.toBytes()); out.write(root); out.write(meta); out.write(leaves)
            dataFile.inputStream().use { it.copyTo(out, 1 shl 20) }
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) { part.copyTo(target, overwrite = true); part.delete() }
    }

    fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()
    fun gunzip(b: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }

    /** Prüft gzip-Integrität (CRC32 + Länge) einer Kachel. */
    fun gzipValid(b: ByteArray): Boolean = try {
        GZIPInputStream(ByteArrayInputStream(b)).use { s -> val buf = ByteArray(16 * 1024); while (s.read(buf) >= 0) Unit }
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Ordnet Einträge in Wurzel + Blatt-Verzeichnisse (wie go-pmtiles): passt alles
     * in die Wurzel, gibt es keine Blätter.
     */
    fun buildDirectories(entries: List<Entry>): Pair<ByteArray, ByteArray> {
        val root = serializeDirectory(entries)
        if (root.size <= MAX_ROOT) return root to ByteArray(0)
        var leafSize = 4096
        while (true) {
            val leaves = ByteArrayOutputStream()
            val rootEntries = ArrayList<Entry>()
            var i = 0
            while (i < entries.size) {
                val chunk = entries.subList(i, minOf(i + leafSize, entries.size))
                val bytes = serializeDirectory(chunk)
                rootEntries += Entry(chunk.first().tileId, leaves.size().toLong(), bytes.size, 0)
                leaves.write(bytes)
                i += leafSize
            }
            val r = serializeDirectory(rootEntries)
            if (r.size <= MAX_ROOT) return r to leaves.toByteArray()
            leafSize = (leafSize * 1.2).toInt()
        }
    }
}

/**
 * Schreibt ein PMTiles-Archiv. Kacheln müssen in aufsteigender Kachel-ID kommen;
 * identische Inhalte (gleicher [contentKey]) werden nur einmal gespeichert.
 */
class PmTilesWriter(private val target: File, private val tmpData: File = File(target.path + ".data")) {
    private val entries = ArrayList<PmTiles.Entry>()
    private val data = RandomAccessFile(tmpData, "rw").apply { setLength(0) }
    private val written = HashMap<Long, Pair<Long, Int>>()
    private var lastId = -1L
    var addressed = 0L; private set
    private var contents = 0L
    var minZoom = 99; private set
    var maxZoom = 0; private set

    /**
     * @param contentKey gleicher Schlüssel ⇒ gleicher Inhalt (Dedupe), z. B. Quell-Offset
     */
    fun add(tileId: Long, bytes: ByteArray, contentKey: Long? = null) {
        require(tileId > lastId) { "Kachel-IDs müssen aufsteigen" }
        lastId = tileId
        addressed++
        val z = TileMath.zoomOf(tileId); minZoom = minOf(minZoom, z); maxZoom = maxOf(maxZoom, z)
        val known = contentKey?.let(written::get)
        val (off, len) = known ?: (data.length() to bytes.size).also {
            data.seek(data.length()); data.write(bytes); contents++
            if (contentKey != null) written[contentKey] = it
        }
        val prev = entries.lastOrNull()
        if (prev != null && prev.offset == off && prev.length == len && prev.tileId + prev.runLength == tileId) {
            entries[entries.lastIndex] = prev.copy(runLength = prev.runLength + 1)
        } else {
            entries += PmTiles.Entry(tileId, off, len, 1)
        }
    }

    fun finish(
        tileType: Int, tileCompression: Int, metadataJson: String,
        bounds: DoubleArray, centerZoom: Int,
    ) {
        data.close()
        PmTiles.assemble(
            target, entries, tmpData, metadataJson, addressed, contents, tileType, tileCompression,
            if (minZoom == 99) 0 else minZoom, maxZoom, bounds, centerZoom,
        )
        tmpData.delete()
    }

    fun abort() { data.close(); tmpData.delete() }
}

/** Liest Kacheln aus einem lokalen PMTiles-Archiv (z. B. Höhendaten für Höhenlinien). */
class PmTilesReader(file: File) : AutoCloseable {
    private val raf = RandomAccessFile(file, "r")
    val header: PmTiles.Header = PmTiles.Header.parse(read(0, PmTiles.HEADER_SIZE))
    private val root = PmTiles.deserializeDirectory(read(header.rootOffset, header.rootLength.toInt()), header.internalCompression)
    private val leafCache = object : LinkedHashMap<Long, List<PmTiles.Entry>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<PmTiles.Entry>>?) = size > 32
    }

    @Synchronized
    private fun read(off: Long, len: Int): ByteArray {
        val b = ByteArray(len); raf.seek(off); raf.readFully(b); return b
    }

    @Synchronized
    fun tile(z: Int, x: Int, y: Int): ByteArray? {
        val id = TileMath.zxyToTileId(z, x, y)
        var dir = root
        repeat(4) {
            val e = PmTiles.find(dir, id) ?: return null
            if (e.runLength > 0) return read(header.dataOffset + e.offset, e.length)
            val off = header.leafOffset + e.offset
            dir = leafCache.getOrPut(off) { PmTiles.deserializeDirectory(read(off, e.length), header.internalCompression) }
        }
        return null
    }

    fun metadata(): String = String(PmTiles.gunzip(read(header.metadataOffset, header.metadataLength.toInt())))

    override fun close() = raf.close()
}
