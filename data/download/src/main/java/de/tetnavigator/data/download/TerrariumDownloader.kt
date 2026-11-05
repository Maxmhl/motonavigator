package de.tetnavigator.data.download

import de.tetnavigator.core.offlinedata.PmTiles
import de.tetnavigator.core.offlinedata.PmTilesWriter
import de.tetnavigator.core.offlinedata.TileMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Lädt Terrarium-Höhenkacheln (PNG) für eine Landesmaske und packt sie in ein
 * lokales PMTiles-Archiv. Bereits geladene Kacheln im Arbeitsordner werden bei
 * einer Fortsetzung übersprungen.
 */
class TerrariumDownloader(private val client: OkHttpClient, private val parallel: Int = 6) {

    suspend fun download(
        tiles: List<Triple<Int, Int, Int>>,
        target: File,
        workDir: File,
        bounds: DoubleArray,
        onProgress: (Int, Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        workDir.mkdirs()
        val done = AtomicLong(0)
        val sem = Semaphore(parallel)
        coroutineScope {
            tiles.map { (z, x, y) ->
                async {
                    sem.withPermit {
                        val f = File(workDir, "${z}_${x}_$y.png")
                        if (!(f.exists() && isPng(f.readBytes()))) fetch(z, x, y, f)
                        onProgress(done.incrementAndGet().toInt(), tiles.size)
                    }
                }
            }.awaitAll()
        }
        val sorted = tiles.map { (z, x, y) -> TileMath.zxyToTileId(z, x, y) to File(workDir, "${z}_${x}_$y.png") }.sortedBy { it.first }
        val w = PmTilesWriter(target)
        try {
            for ((id, f) in sorted) if (f.exists()) w.add(id, f.readBytes())
            w.finish(
                PmTiles.TYPE_PNG, PmTiles.COMPRESSION_NONE,
                """{"name":"Terrarium DEM","format":"png","encoding":"terrarium","attribution":"Höhendaten: Mapzen/Tilezen Terrain Tiles (AWS Open Data)"}""",
                bounds, 8,
            )
        } catch (e: Exception) {
            w.abort(); throw e
        }
        workDir.deleteRecursively()
    }

    private fun fetch(z: Int, x: Int, y: Int, f: File) {
        repeat(3) { attempt ->
            try {
                client.newCall(Request.Builder().url(DirectSources.terrarium(z, x, y)).build()).execute().use { r ->
                    if (r.code == 404 || r.code == 403) return // keine Kachel (Meer)
                    if (!r.isSuccessful) throw DownloadException("Höhendaten HTTP ${r.code}")
                    val b = r.body?.bytes() ?: throw DownloadException("Leere Höhenkachel")
                    if (!isPng(b)) throw DownloadException("Höhenkachel beschädigt")
                    val tmp = File(f.path + ".part"); tmp.writeBytes(b); tmp.renameTo(f)
                    return
                }
            } catch (e: OfflineLockedException) {
                throw e
            } catch (e: Exception) {
                if (attempt == 2) throw e
                Thread.sleep(1000L shl attempt)
            }
        }
    }

    companion object {
        private val SIG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        /** PNG-Signatur am Anfang und IEND-Chunk am Ende (vollständig übertragen). */
        fun isPng(b: ByteArray): Boolean {
            if (b.size < 20) return false
            for (i in SIG.indices) if (b[i] != SIG[i]) return false
            val iend = String(b, b.size - 8, 4, Charsets.US_ASCII)
            return iend == "IEND"
        }
    }
}
