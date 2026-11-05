package de.tetnavigator.core.offlinedata

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PmTilesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun hilbertIdsMatchReferenceImplementation() {
        // Referenzwerte aus der offiziellen pmtiles-Python-Bibliothek
        val ref = listOf(
            Triple(0, 0, 0) to 0L, Triple(1, 0, 0) to 1L, Triple(1, 1, 0) to 4L, Triple(1, 1, 1) to 3L, Triple(1, 0, 1) to 2L,
            Triple(2, 3, 1) to 17L, Triple(10, 543, 287) to 1244501L, Triple(14, 8710, 4580) to 318941571L,
            Triple(15, 17420, 9161) to 1275766286L, Triple(12, 2000, 1000) to 7340821L,
        )
        for ((zxy, expected) in ref) {
            val id = TileMath.zxyToTileId(zxy.first, zxy.second, zxy.third)
            assertThat(id).isEqualTo(expected)
            assertThat(TileMath.zoomOf(id)).isEqualTo(zxy.first)
        }
    }

    @Test fun directoryRoundTrip() {
        val e = listOf(PmTiles.Entry(0, 0, 100, 1), PmTiles.Entry(1, 100, 50, 3), PmTiles.Entry(10, 10, 7, 1), PmTiles.Entry(11, 17, 9, 0))
        assertThat(PmTiles.deserializeDirectory(PmTiles.serializeDirectory(e))).isEqualTo(e)
    }

    @Test fun writeAndReadArchiveWithLeavesAndDedupe() {
        val f = File(tmp.root, "t.pmtiles")
        val w = PmTilesWriter(f)
        // 120.000 Kacheln mit unregelmäßigen IDs/Längen (erzwingt Blatt-Verzeichnisse), jede 10. identisch („Meer“)
        val rnd = java.util.Random(1)
        val ids = (0 until 120_000).map { TileMath.zxyToTileId(9, it % 512, it / 512) }.filter { rnd.nextInt(3) > 0 || it % 10 == 0L }.sorted()
        for (id in ids) {
            val sea = id % 10 == 0L
            w.add(id, if (sea) "sea".toByteArray() else ("tile-$id-" + "x".repeat((id % 97).toInt())).toByteArray(), if (sea) -1L else null)
        }
        w.finish(PmTiles.TYPE_PNG, PmTiles.COMPRESSION_NONE, """{"name":"test"}""", doubleArrayOf(-180.0, -85.0, 180.0, 85.0), 3)
        PmTilesReader(f).use { r ->
            assertThat(r.header.leafLength).isGreaterThan(0L)
            assertThat(r.header.addressedTiles).isEqualTo(ids.size.toLong())
            assertThat(r.metadata()).contains("test")
            val idSet = ids.toHashSet()
            for (i in 0 until 120_000 step 997) {
                val x = i % 512; val y = i / 512
                val id = TileMath.zxyToTileId(9, x, y)
                val t = r.tile(9, x, y)
                if (id !in idSet) { assertThat(t).isNull(); continue }
                val expect = if (id % 10 == 0L) "sea" else "tile-$id-" + "x".repeat((id % 97).toInt())
                assertThat(String(t!!)).isEqualTo(expect)
            }
            assertThat(r.tile(8, 0, 0)).isNull()
        }
    }

    @Test fun countryMaskIsTighterThanBoundingBox() {
        val shapes = listOf(File("src/main/assets/country_shapes.json"), File("core/offlinedata/src/main/assets/country_shapes.json")).first { it.exists() }
            .inputStream().use(CountryShape::parseAll)
        val no = shapes.getValue("NO")
        assertThat(no.contains(10.75, 59.91)).isTrue()   // Oslo
        assertThat(no.contains(18.07, 59.33)).isFalse()  // Stockholm
        val mask = no.tileIds(12)
        var bbox = 0L
        for (z in 0..12) {
            val x0 = TileMath.lonToX(4.5, z); val x1 = TileMath.lonToX(31.2, z)
            val y0 = TileMath.latToY(71.2, z); val y1 = TileMath.latToY(57.9, z)
            bbox += (x1 - x0 + 1).toLong() * (y1 - y0 + 1)
        }
        println("Norwegen z0–12: Maske ${mask.size} Kacheln, Bounding-Box $bbox")
        assertThat(mask.size.toLong()).isLessThan(bbox * 6 / 10)
        assertThat(mask.toList()).isInOrder()
        // Oslo-Kachel ist drin, Stockholm-Kachel nicht
        assertThat(mask.binarySearch(TileMath.zxyToTileId(12, TileMath.lonToX(10.75, 12), TileMath.latToY(59.91, 12)))).isAtLeast(0)
        assertThat(mask.binarySearch(TileMath.zxyToTileId(12, TileMath.lonToX(18.07, 12), TileMath.latToY(59.33, 12)))).isLessThan(0)
    }
}
