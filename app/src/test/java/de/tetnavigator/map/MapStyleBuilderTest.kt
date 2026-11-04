package de.tetnavigator.map

import com.google.common.truth.Truth.assertThat
import de.tetnavigator.core.designsystem.DarkTetColors
import de.tetnavigator.core.designsystem.DayTetColors
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MapStyleBuilderTest {
    private val f = File("/data/packages/NO/no.pmtiles")

    @Test fun styleIsOfflineOnly() {
        for (colors in listOf(DarkTetColors, DayTetColors)) {
            val json = MapStyleBuilder.build(colors, listOf("NO" to f), listOf("NO" to File("/x/no-dem.pmtiles")), listOf("NO" to File("/x/no-contours.pmtiles")), MapLayers())
            val o = JSONObject(json)
            assertThat(o.getInt("version")).isEqualTo(8)
            assertThat(o.getString("glyphs")).startsWith("asset://")
            // Harte Regel 1: keine Online-Ressourcen im Kartenstil
            assertThat(json).doesNotContain("http://")
            assertThat(json).doesNotContain("https://")
            val sources = o.getJSONObject("sources")
            assertThat(sources.getJSONObject("map-NO").getString("url")).isEqualTo("pmtiles://file:///data/packages/NO/no.pmtiles")
            assertThat(sources.getJSONObject("dem-NO").getString("type")).isEqualTo("raster-dem")
            val ids = (0 until o.getJSONArray("layers").length()).map { o.getJSONArray("layers").getJSONObject(it).getString("id") }
            assertThat(ids).containsAtLeast("background", "hillshade-NO", "water-NO", "track-NO", "contour-NO", "place-NO")
            assertThat(ids.toSet().size).isEqualTo(ids.size)
        }
    }

    @Test fun layersCanBeDisabled() {
        val json = MapStyleBuilder.build(DarkTetColors, listOf("NO" to f), listOf("NO" to f), listOf("NO" to f), MapLayers(hillshade = false, contours = false))
        assertThat(json).doesNotContain("hillshade-NO")
        assertThat(json).doesNotContain("contour-NO")
    }

    @Test fun glyphAssetsExistForStyleFonts() {
        val root = listOf(File("src/main/assets/glyphs"), File("app/src/main/assets/glyphs")).first { it.exists() }
        for (font in listOf(MapStyleBuilder.FONT, MapStyleBuilder.FONT_BOLD)) {
            assertThat(File(root, "$font/0-255.pbf").exists()).isTrue()
        }
    }
}
