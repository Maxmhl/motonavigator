package de.tetnavigator.data.download

import com.google.common.truth.Truth.assertThat
import de.tetnavigator.core.offlinedata.CountryShape
import de.tetnavigator.core.offlinedata.PmTiles
import de.tetnavigator.core.offlinedata.PmTilesReader
import de.tetnavigator.core.offlinedata.TileMath
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Echter Download aus den öffentlichen Quellen (nur mit -PtetLiveOut=/pfad):
 * Luxemburg bis Zoom 12 aus dem aktuellen Protomaps-Build + Terrarium bis Zoom 8.
 */
class DirectSourcesLiveTest {
    private val out = System.getProperty("tet.live.out")?.let(::File)
    private val client = HttpClients.create { true }
    private fun lu(): CountryShape =
        listOf(File("../../core/offlinedata/src/main/assets/country_shapes.json"), File("core/offlinedata/src/main/assets/country_shapes.json"))
            .first { it.exists() }.inputStream().use(CountryShape::parseAll).getValue("LU")

    @Test fun extractsLuxembourgMapAndResumes() = runBlocking {
        assumeTrue(out != null)
        val ex = ProtomapsExtractor(client)
        val build = ex.latestBuild()
        val plan = ex.plan(build, lu().tileIds(12))
        println("Build $build: ${plan.tileCount} Kacheln, ${plan.blobOffset.size} Blobs, ${plan.tileBytes / 1e6} MB, Verzeichnisse ${plan.directoryBytes / 1e3} kB")
        val target = File(out, "lu-protomaps.pmtiles")
        val work = File(out, "work-lu")
        // Abbruch nach dem ersten Teil simulieren: Fortschritt nach > 30 % abbrechen
        try {
            ex.extract(plan, target, work) { d, t -> if (d > t * 0.3) throw InterruptedException("Abbruch-Test") }
        } catch (_: InterruptedException) { }
        assertThat(File(work, "state.txt").exists()).isTrue()
        var resumedFrom = -1L
        ex.extract(plan, target, work) { d, _ -> if (resumedFrom < 0) resumedFrom = d }
        assertThat(resumedFrom).isGreaterThan(0L)
        PmTilesReader(target).use { r ->
            assertThat(r.header.maxZoom).isEqualTo(12)
            val t = r.tile(12, TileMath.lonToX(6.13, 12), TileMath.latToY(49.61, 12))!! // Luxemburg-Stadt
            assertThat(PmTiles.gzipValid(t)).isTrue()
            assertThat(r.metadata()).contains("vector_layers")
            assertThat(r.tile(12, TileMath.lonToX(10.0, 12), TileMath.latToY(50.0, 12))).isNull() // Franken – außerhalb
        }
        println("Archiv: ${target.length() / 1e6} MB")
    }

    @Test fun downloadsTerrarium() = runBlocking {
        assumeTrue(out != null)
        val tiles = (0..8).flatMap { lu().tilesAt(it, 5_000.0) }
        val target = File(out, "lu-terrarium.pmtiles")
        TerrariumDownloader(client).download(tiles, target, File(out, "work-dem"), doubleArrayOf(5.7, 49.4, 6.6, 50.2)) { _, _ -> }
        PmTilesReader(target).use { r ->
            val png = r.tile(8, TileMath.lonToX(6.13, 8), TileMath.latToY(49.61, 8))!!
            assertThat(TerrariumDownloader.isPng(png)).isTrue()
            assertThat(r.header.tileType).isEqualTo(PmTiles.TYPE_PNG)
        }
        println("Terrarium: ${tiles.size} Kacheln, ${target.length() / 1e3} kB")
    }

    @Test fun rd5TilesForNorway() {
        val no = listOf(File("../../core/offlinedata/src/main/assets/country_shapes.json"), File("core/offlinedata/src/main/assets/country_shapes.json"))
            .first { it.exists() }.inputStream().use(CountryShape::parseAll).getValue("NO")
        val t = DirectSources.rd5Tiles { a, b, c, d -> no.intersects(a, b, c, d) }
        println("NO rd5: $t")
        assertThat(t).contains("E10_N60")
        assertThat(t).doesNotContain("E25_N55")
    }

    @Test fun planSizesNorway() = runBlocking {
        assumeTrue(out != null && System.getProperty("tet.live.sizes") != null)
        val no = listOf(File("../../core/offlinedata/src/main/assets/country_shapes.json"), File("core/offlinedata/src/main/assets/country_shapes.json"))
            .first { it.exists() }.inputStream().use(CountryShape::parseAll).getValue("NO")
        val ex = ProtomapsExtractor(client)
        val build = ex.latestBuild()
        for (z in 12..14) {
            val p = ex.plan(build, no.tileIds(z))
            println("NO bis z$z: ${p.tileCount} Kacheln, ${"%.0f".format(p.tileBytes / 1e6)} MB")
        }
        println("NO Terrarium z10: ${(0..10).sumOf { no.tilesAt(it, 5_000.0).size }} Kacheln")
    }
}
