package de.tetnavigator.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.hypot

class ContoursTest {
    @Test fun terrariumDecoding() {
        // 0 m ↔ R=128, G=0, B=0 ; 1000 m ↔ 32768+1000 = 33768 = 131*256 + 232
        assertThat(Contours.terrariumElevation((0xff shl 24) or (128 shl 16))).isEqualTo(0f)
        assertThat(Contours.terrariumElevation((0xff shl 24) or (131 shl 16) or (232 shl 8))).isEqualTo(1000f)
    }

    @Test fun coneGivesClosedConcentricRings() {
        val size = 64
        // Kegel: Gipfel 500 m in der Mitte, fällt 10 m pro Pixel
        val elev = FloatArray(size * size) { i ->
            val x = i % size; val y = i / size
            (500 - 10 * hypot(x - 31.5, y - 31.5)).toFloat()
        }
        val lines = Contours.generate(elev, size, 10, 543, 287, interval = 100, step = 1)
        val levels = lines.map { it.elevation }.toSet()
        assertThat(levels).containsAtLeast(200, 300, 400)
        // jede Höhe ist genau EINE geschlossene Linie
        for (lv in listOf(300, 400)) {
            val l = lines.filter { it.elevation == lv }
            assertThat(l).hasSize(1)
            val ring = l.single()
            assertThat(ring.lon.first()).isWithin(1e-9).of(ring.lon.last())
            assertThat(ring.lat.first()).isWithin(1e-9).of(ring.lat.last())
        }
        // Koordinaten liegen in der Kachel 10/543/287
        val all = lines.flatMap { it.lon.toList() }
        assertThat(all.min()).isAtLeast(10.898)
        assertThat(all.max()).isAtMost(11.25)
    }

    @Test fun flatTerrainHasNoContours() {
        assertThat(Contours.generate(FloatArray(32 * 32) { 123f }, 32, 10, 0, 0, 20)).isEmpty()
    }
}
