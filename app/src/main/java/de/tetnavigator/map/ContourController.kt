package de.tetnavigator.map

import android.graphics.BitmapFactory
import android.util.LruCache
import de.tetnavigator.core.offlinedata.PmTilesReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Liefert Höhenlinien für einen Kartenausschnitt aus den lokalen
 * Terrarium-Höhendaten (Direkt-Download hat keine fertigen Höhenlinien).
 */
class ContourController(files: List<File>, private val demZoom: Int = 10) : AutoCloseable {
    private val readers = files.mapNotNull { runCatching { PmTilesReader(it) }.getOrNull() }
    private val cache = LruCache<String, List<ContourLine>>(24)

    val available get() = readers.isNotEmpty()

    suspend fun contours(minLon: Double, minLat: Double, maxLon: Double, maxLat: Double, zoom: Double): List<ContourLine> =
        withContext(Dispatchers.Default) {
            if (!available || zoom < 12) return@withContext emptyList()
            val interval = Contours.intervalFor(zoom)
            val tiles = Contours.tilesFor(minLon, minLat, maxLon, maxLat, demZoom).take(9)
            tiles.flatMap { (x, y) ->
                val key = "$x/$y/$interval"
                cache.get(key) ?: compute(x, y, interval).also { cache.put(key, it) }
            }
        }

    private fun compute(x: Int, y: Int, interval: Int): List<ContourLine> {
        val png = readers.firstNotNullOfOrNull { it.tile(demZoom, x, y) } ?: return emptyList()
        val bmp = BitmapFactory.decodeByteArray(png, 0, png.size, BitmapFactory.Options().apply { inPremultiplied = false }) ?: return emptyList()
        val size = bmp.width
        val px = IntArray(size * size)
        bmp.getPixels(px, 0, size, 0, 0, size, size)
        bmp.recycle()
        val elev = FloatArray(px.size) { Contours.terrariumElevation(px[it]) }
        return Contours.generate(elev, size, demZoom, x, y, interval, step = if (interval <= 20) 1 else 2)
    }

    override fun close() = readers.forEach { it.close() }
}
