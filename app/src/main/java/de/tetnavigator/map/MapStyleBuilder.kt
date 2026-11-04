package de.tetnavigator.map

import androidx.compose.ui.graphics.toArgb
import de.tetnavigator.core.designsystem.TetColors
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Welche Ebenen sichtbar sind. */
data class MapLayers(
    val hillshade: Boolean = true,
    val contours: Boolean = true,
)

/**
 * Baut den Offroad-Kartenstil (MapLibre-Style-JSON) aus den lokal
 * installierten PMTiles-Paketen. Glyphen kommen aus den App-Assets –
 * keine einzige Online-Ressource.
 *
 * Erwartete Schemas:
 *  - Karte: OpenMapTiles-Schema (Planetiler-Standard)
 *  - Höhenlinien: Vektor-Layer `contour` mit Attribut `ele`
 *  - Schummerung: raster-dem (Terrain-RGB / "mapbox"-Kodierung)
 */
object MapStyleBuilder {
    const val FONT = "NotoSansRegular"
    const val FONT_BOLD = "NotoSansMedium"

    fun build(
        colors: TetColors,
        maps: List<Pair<String, File>>,
        hillshade: List<Pair<String, File>>,
        contours: List<Pair<String, File>>,
        layers: MapLayers,
    ): String {
        val sources = JSONObject()
        val out = JSONArray()
        val c = Palette.of(colors)

        out.put(layer("background", "background").put("paint", JSONObject().put("background-color", c.bg)))

        // Schummerung zuerst (unter allem anderen)
        if (layers.hillshade) hillshade.forEach { (cc, f) ->
            val id = "dem-$cc"
            sources.put(id, JSONObject().put("type", "raster-dem").put("url", pm(f)).put("tileSize", 256).put("encoding", "mapbox"))
            out.put(
                layer("hillshade-$cc", "hillshade").put("source", id).put(
                    "paint",
                    JSONObject()
                        .put("hillshade-shadow-color", c.shadow)
                        .put("hillshade-highlight-color", c.highlight)
                        .put("hillshade-accent-color", c.shadow)
                        .put("hillshade-exaggeration", 0.35),
                ),
            )
        }

        maps.forEach { (cc, f) ->
            val src = "map-$cc"
            sources.put(src, JSONObject().put("type", "vector").put("url", pm(f)))
            mapLayers(out, src, cc, c)
        }

        if (layers.contours) contours.forEach { (cc, f) ->
            val src = "contour-$cc"
            sources.put(src, JSONObject().put("type", "vector").put("url", pm(f)))
            out.put(
                layer("contour-$cc", "line").put("source", src).put("source-layer", "contour").put("minzoom", 11)
                    .put("paint", JSONObject().put("line-color", c.contour).put("line-width", zoom(11, 0.6, 16, 1.2)))
                    .put("filter", JSONArray("[\"!=\", [\"%\", [\"to-number\", [\"get\", \"ele\"]], 50], 0]")),
            )
            out.put(
                layer("contour-index-$cc", "line").put("source", src).put("source-layer", "contour").put("minzoom", 10)
                    .put("paint", JSONObject().put("line-color", c.contourIndex).put("line-width", zoom(10, 0.8, 16, 1.8)))
                    .put("filter", JSONArray("[\"==\", [\"%\", [\"to-number\", [\"get\", \"ele\"]], 50], 0]")),
            )
            out.put(
                layer("contour-label-$cc", "symbol").put("source", src).put("source-layer", "contour").put("minzoom", 13)
                    .put("filter", JSONArray("[\"==\", [\"%\", [\"to-number\", [\"get\", \"ele\"]], 100], 0]"))
                    .put("layout", JSONObject().put("symbol-placement", "line").put("text-field", JSONArray("[\"concat\", [\"to-string\", [\"get\", \"ele\"]], \" m\"]"))
                        .put("text-font", JSONArray().put(FONT)).put("text-size", 10))
                    .put("paint", JSONObject().put("text-color", c.label2).put("text-halo-color", c.bg).put("text-halo-width", 1.2)),
            )
        }

        // Ortsnamen ganz oben
        maps.forEach { (cc, _) -> labelLayers(out, "map-$cc", cc, c) }

        return JSONObject()
            .put("version", 8)
            .put("name", "TET Offroad")
            .put("glyphs", "asset://glyphs/{fontstack}/{range}.pbf")
            .put("sources", sources)
            .put("layers", out)
            .toString()
    }

    private fun pm(f: File) = "pmtiles://file://${f.absolutePath}"

    private fun layer(id: String, type: String) = JSONObject().put("id", id).put("type", type)

    private fun zoom(z1: Int, v1: Double, z2: Int, v2: Double) =
        JSONArray("[\"interpolate\", [\"exponential\", 1.4], [\"zoom\"], $z1, $v1, $z2, $v2]")

    private fun mapLayers(out: JSONArray, src: String, cc: String, c: Palette) {
        fun fill(id: String, srcLayer: String, color: String, filter: String? = null, opacity: Double = 1.0, minzoom: Int = 0) =
            out.put(layer("$id-$cc", "fill").put("source", src).put("source-layer", srcLayer).put("minzoom", minzoom)
                .put("paint", JSONObject().put("fill-color", color).put("fill-opacity", opacity))
                .apply { if (filter != null) put("filter", JSONArray(filter)) })

        fill("landcover-wood", "landcover", c.wood, "[\"==\", [\"get\", \"class\"], \"wood\"]")
        fill("landcover-grass", "landcover", c.grass, "[\"in\", [\"get\", \"class\"], [\"literal\", [\"grass\", \"farmland\"]]]", 0.6)
        fill("landcover-wet", "landcover", c.wet, "[\"==\", [\"get\", \"class\"], \"wetland\"]", 0.7)
        fill("landcover-rock", "landcover", c.rock, "[\"in\", [\"get\", \"class\"], [\"literal\", [\"rock\", \"sand\"]]]", 0.7)
        fill("landcover-ice", "landcover", c.ice, "[\"==\", [\"get\", \"class\"], \"ice\"]")
        fill("landuse", "landuse", c.urban, "[\"in\", [\"get\", \"class\"], [\"literal\", [\"residential\", \"suburb\", \"neighbourhood\", \"commercial\", \"industrial\"]]]", 0.8)
        fill("park", "park", c.grass, null, 0.4)
        fill("water", "water", c.water)
        out.put(
            layer("waterway-$cc", "line").put("source", src).put("source-layer", "waterway")
                .put("paint", JSONObject().put("line-color", c.water).put("line-width", zoom(8, 0.5, 16, 3.0))),
        )
        fill("building", "building", c.building, null, 0.9, 13)

        fun road(id: String, filter: String, color: String, w1: Double, w2: Double, dash: String? = null, minzoom: Int = 0, casing: String? = null) {
            if (casing != null) {
                out.put(
                    layer("$id-case-$cc", "line").put("source", src).put("source-layer", "transportation").put("minzoom", minzoom)
                        .put("filter", JSONArray(filter))
                        .put("layout", JSONObject().put("line-cap", "round").put("line-join", "round"))
                        .put("paint", JSONObject().put("line-color", casing).put("line-width", zoom(8, w1 + 1, 18, w2 + 3))),
                )
            }
            out.put(
                layer("$id-$cc", "line").put("source", src).put("source-layer", "transportation").put("minzoom", minzoom)
                    .put("filter", JSONArray(filter))
                    .put("layout", JSONObject().put("line-cap", if (dash == null) "round" else "butt").put("line-join", "round"))
                    .put("paint", JSONObject().put("line-color", color).put("line-width", zoom(8, w1, 18, w2)).apply {
                        if (dash != null) put("line-dasharray", JSONArray(dash))
                    }),
            )
        }
        road("path", "[\"in\", [\"get\", \"class\"], [\"literal\", [\"path\"]]]", c.path, 0.5, 1.5, "[2, 2]", 13)
        road("track", "[\"==\", [\"get\", \"class\"], \"track\"]", c.track, 0.8, 3.0, "[3, 1.5]", 11)
        road("minor-unpaved", "[\"all\", [\"in\", [\"get\", \"class\"], [\"literal\", [\"minor\", \"service\"]]], [\"==\", [\"get\", \"surface\"], \"unpaved\"]]", c.track, 0.8, 4.0, "[4, 1]", 11)
        road("minor", "[\"all\", [\"in\", [\"get\", \"class\"], [\"literal\", [\"minor\", \"service\"]]], [\"!=\", [\"get\", \"surface\"], \"unpaved\"]]", c.minor, 0.6, 5.0, null, 10)
        road("secondary", "[\"in\", [\"get\", \"class\"], [\"literal\", [\"secondary\", \"tertiary\"]]]", c.secondary, 0.8, 7.0, null, 7, c.bg)
        road("primary", "[\"in\", [\"get\", \"class\"], [\"literal\", [\"primary\", \"trunk\"]]]", c.primary, 1.0, 9.0, null, 5, c.bg)
        road("motorway", "[\"==\", [\"get\", \"class\"], \"motorway\"]", c.motorway, 1.2, 10.0, null, 4, c.bg)
        road("ferry", "[\"==\", [\"get\", \"class\"], \"ferry\"]", c.water2, 0.8, 2.0, "[3, 3]", 6)
        out.put(
            layer("boundary-$cc", "line").put("source", src).put("source-layer", "boundary")
                .put("filter", JSONArray("[\"<=\", [\"get\", \"admin_level\"], 2]"))
                .put("paint", JSONObject().put("line-color", c.boundary).put("line-width", 1.5).put("line-dasharray", JSONArray("[4, 2]"))),
        )
    }

    private fun labelLayers(out: JSONArray, src: String, cc: String, c: Palette) {
        out.put(
            layer("road-label-$cc", "symbol").put("source", src).put("source-layer", "transportation_name").put("minzoom", 13)
                .put("layout", JSONObject().put("symbol-placement", "line").put("text-field", JSONArray("[\"coalesce\", [\"get\", \"name:latin\"], [\"get\", \"name\"]]"))
                    .put("text-font", JSONArray().put(FONT)).put("text-size", 11))
                .put("paint", JSONObject().put("text-color", c.label2).put("text-halo-color", c.bg).put("text-halo-width", 1.5)),
        )
        out.put(
            layer("peak-$cc", "symbol").put("source", src).put("source-layer", "mountain_peak").put("minzoom", 11)
                .put("layout", JSONObject().put("text-field", JSONArray("[\"concat\", \"▲ \", [\"coalesce\", [\"get\", \"name:latin\"], [\"get\", \"name\"], \"\"], \" \", [\"to-string\", [\"coalesce\", [\"get\", \"ele\"], \"\"]]]"))
                    .put("text-font", JSONArray().put(FONT)).put("text-size", 11).put("text-anchor", "top"))
                .put("paint", JSONObject().put("text-color", c.label2).put("text-halo-color", c.bg).put("text-halo-width", 1.2)),
        )
        out.put(
            layer("place-$cc", "symbol").put("source", src).put("source-layer", "place")
                .put("filter", JSONArray("[\"in\", [\"get\", \"class\"], [\"literal\", [\"city\", \"town\", \"village\", \"hamlet\"]]]"))
                .put("layout", JSONObject().put("text-field", JSONArray("[\"coalesce\", [\"get\", \"name:latin\"], [\"get\", \"name\"]]"))
                    .put("text-font", JSONArray().put(FONT_BOLD))
                    .put("text-size", JSONArray("[\"match\", [\"get\", \"class\"], \"city\", 16, \"town\", 14, \"village\", 12, 11]"))
                    .put("text-transform", "uppercase").put("text-letter-spacing", 0.08))
                .put("paint", JSONObject().put("text-color", c.label).put("text-halo-color", c.bg).put("text-halo-width", 1.6)),
        )
    }

    /** Kartenfarben aus den Design-Tokens. */
    private data class Palette(
        val bg: String, val wood: String, val grass: String, val wet: String, val rock: String, val ice: String,
        val urban: String, val water: String, val water2: String, val building: String,
        val path: String, val track: String, val minor: String, val secondary: String, val primary: String, val motorway: String,
        val boundary: String, val contour: String, val contourIndex: String, val label: String, val label2: String,
        val shadow: String, val highlight: String,
    ) {
        companion object {
            fun of(t: TetColors): Palette = if (t.isDark) Palette(
                bg = hex(t.mapBg), wood = "#1C2619", grass = "#1E231A", wet = "#1B2422", rock = "#24251F", ice = "#2C3133",
                urban = "#22231E", water = "#1B2B33", water2 = "#2E4A57", building = "#2A2C25",
                path = "#55584C", track = hex(t.sand), minor = "#4A4D43", secondary = "#6B6E62", primary = "#8A8C7E", motorway = "#A09A86",
                boundary = "#7A6A4A", contour = hex(t.contour), contourIndex = "#33372B", label = hex(t.text), label2 = hex(t.sand),
                shadow = "#000000", highlight = "#3A3D33",
            ) else Palette(
                bg = hex(t.mapBg), wood = "#C9D3B0", grass = "#D8DCC0", wet = "#C8D6CF", rock = "#D6D0C0", ice = "#EEF2F2",
                urban = "#DDD4C2", water = "#9CC0CF", water2 = "#5C8EA3", building = "#CBBFA8",
                path = "#8A7F63", track = "#8A6A3E", minor = "#FFFFFF", secondary = "#F3E7C8", primary = "#F2D68A", motorway = "#E9B872",
                boundary = "#8A6A9A", contour = hex(t.contour), contourIndex = "#B8AA8A", label = hex(t.text), label2 = hex(t.sand),
                shadow = "#6B6150", highlight = "#FFFFFF",
            )

            private fun hex(c: androidx.compose.ui.graphics.Color): String =
                String.format("#%06X", c.toArgb() and 0xFFFFFF)
        }
    }
}
