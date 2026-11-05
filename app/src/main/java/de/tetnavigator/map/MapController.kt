package de.tetnavigator.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.graphics.toArgb
import de.tetnavigator.core.common.LatLon
import de.tetnavigator.core.database.HazardPinEntity
import de.tetnavigator.core.designsystem.TetColors
import de.tetnavigator.core.offlinedata.Poi
import de.tetnavigator.core.offlinedata.SurfaceClass
import de.tetnavigator.feature.navigation.ActiveTrack
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Track-, Positions- und Pin-Ebenen über dem Offroad-Stil.
 * Track 7 dp accent mit 13 dp dunklem Rand, gefahrener Teil gestrichelt braun,
 * Position als oranger Pfeil mit Halo, Gefahren-Pins als gelbe/rote Dreiecke.
 */
class MapController(private val map: MapLibreMap, private val density: Float) {
    private var style: Style? = null
    private var colors: TetColors? = null
    var follow = true

    fun onStyle(s: Style, c: TetColors) {
        style = s; colors = c
        // Höhenlinien unter die ersten Beschriftungen legen
        val firstSymbol = s.layers.firstOrNull { it is SymbolLayer }?.id
        s.addSource(GeoJsonSource(SRC_CONTOURS, FeatureCollection.fromFeatures(emptyList())))
        val contour = LineLayer("contours-live", SRC_CONTOURS).withProperties(PropertyFactory.lineColor(c.contour.toArgb()), PropertyFactory.lineWidth(1.1f))
            .withFilter(Expression.neq(Expression.get("index"), Expression.literal(true)))
        val index = LineLayer("contours-live-index", SRC_CONTOURS).withProperties(PropertyFactory.lineColor(c.outline.toArgb()), PropertyFactory.lineWidth(1.8f))
            .withFilter(Expression.eq(Expression.get("index"), Expression.literal(true)))
        val label = SymbolLayer("contours-live-label", SRC_CONTOURS).withProperties(
            PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
            PropertyFactory.textField(Expression.concat(Expression.toString(Expression.get("ele")), Expression.literal(" m"))),
            PropertyFactory.textFont(arrayOf(MapStyleBuilder.FONT)), PropertyFactory.textSize(10f),
            PropertyFactory.textColor(c.sand.toArgb()), PropertyFactory.textHaloColor(c.bg.toArgb()), PropertyFactory.textHaloWidth(1.2f),
        ).withFilter(Expression.eq(Expression.get("index"), Expression.literal(true)))
        if (firstSymbol != null) { s.addLayerBelow(contour, firstSymbol); s.addLayerBelow(index, firstSymbol) } else { s.addLayer(contour); s.addLayer(index) }
        s.addLayer(label)
        addImages(s, c)
        val empty = FeatureCollection.fromFeatures(emptyList())
        for (id in listOf(SRC_TRACK, SRC_RIDDEN, SRC_CHANGED, SRC_SURFACE, SRC_AUX, SRC_HAZARD, SRC_POI, SRC_POS, SRC_ALL_TRACKS)) {
            s.addSource(GeoJsonSource(id, empty))
        }
        val a = c.accent.toArgb(); val dark = c.bg.toArgb()
        s.addLayer(LineLayer("all-tracks", SRC_ALL_TRACKS).withProperties(PropertyFactory.lineColor(a), PropertyFactory.lineOpacity(0.45f), PropertyFactory.lineWidth(3f), PropertyFactory.lineCap(Property.LINE_CAP_ROUND)))
        s.addLayer(LineLayer("track-case", SRC_TRACK).withProperties(PropertyFactory.lineColor(dark), PropertyFactory.lineWidth(13f), PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
        s.addLayer(LineLayer("track", SRC_TRACK).withProperties(PropertyFactory.lineColor(a), PropertyFactory.lineWidth(7f), PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
        s.addLayer(
            LineLayer("track-surface", SRC_SURFACE).withProperties(
                PropertyFactory.lineWidth(4f), PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineColor(
                    Expression.match(
                        Expression.get("s"), Expression.color(c.surfaceUnknown.toArgb()),
                        Expression.stop(SurfaceClass.GRAVEL.code, Expression.color(c.surfaceGravel.toArgb())),
                        Expression.stop(SurfaceClass.DIRT.code, Expression.color(c.surfaceDirt.toArgb())),
                        Expression.stop(SurfaceClass.ASPHALT.code, Expression.color(c.surfaceAsphalt.toArgb())),
                    ),
                ),
            ),
        )
        s.addLayer(LineLayer("track-changed", SRC_CHANGED).withProperties(PropertyFactory.lineColor(c.warning.toArgb()), PropertyFactory.lineWidth(5f), PropertyFactory.lineCap(Property.LINE_CAP_ROUND)))
        s.addLayer(LineLayer("track-ridden", SRC_RIDDEN).withProperties(PropertyFactory.lineColor(c.riddenTrack.toArgb()), PropertyFactory.lineWidth(5f), PropertyFactory.lineDasharray(arrayOf(1f, 1.2f))))
        s.addLayer(LineLayer("aux-route", SRC_AUX).withProperties(PropertyFactory.lineColor(c.warning.toArgb()), PropertyFactory.lineWidth(6f), PropertyFactory.lineDasharray(arrayOf(1.5f, 1f)), PropertyFactory.lineCap(Property.LINE_CAP_ROUND)))
        s.addLayer(
            SymbolLayer("pois", SRC_POI).withProperties(
                PropertyFactory.iconImage(Expression.concat(Expression.literal("poi-"), Expression.get("c"))),
                PropertyFactory.iconAllowOverlap(false), PropertyFactory.iconSize(1f),
            ),
        )
        s.addLayer(
            SymbolLayer("hazards", SRC_HAZARD).withProperties(
                PropertyFactory.iconImage(Expression.get("img")), PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
            ),
        )
        s.addLayer(CircleLayer("pos-halo", SRC_POS).withProperties(PropertyFactory.circleRadius(22f), PropertyFactory.circleColor(a), PropertyFactory.circleOpacity(0.22f)))
        s.addLayer(
            SymbolLayer("pos", SRC_POS).withProperties(
                PropertyFactory.iconImage(IMG_ARROW), PropertyFactory.iconRotate(Expression.get("b")),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
            ),
        )
    }

    private fun src(id: String) = style?.getSourceAs<GeoJsonSource>(id)

    fun setTrack(track: ActiveTrack?, surfaceColoring: Boolean) {
        val t = track
        if (t == null) {
            for (id in listOf(SRC_TRACK, SRC_CHANGED, SRC_SURFACE, SRC_RIDDEN)) src(id)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            return
        }
        src(SRC_TRACK)?.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(t.vertices.map { Point.fromLngLat(it.lon, it.lat) })))
        // geänderte Abschnitte (gelb)
        val changed = runs(t.vertices.indices.toList()) { t.vertices[it].changed }
            .map { r -> Feature.fromGeometry(LineString.fromLngLats(r.map { Point.fromLngLat(t.vertices[it].lon, t.vertices[it].lat) })) }
        src(SRC_CHANGED)?.setGeoJson(FeatureCollection.fromFeatures(changed))
        // Untergrund-Färbung
        val surf = if (!surfaceColoring) emptyList() else {
            val out = ArrayList<Feature>()
            var start = 0
            for (i in 1..t.vertices.size) {
                if (i == t.vertices.size || t.vertices[i].surface != t.vertices[start].surface) {
                    val pts = (start..minOf(i, t.vertices.size - 1)).map { Point.fromLngLat(t.vertices[it].lon, t.vertices[it].lat) }
                    if (pts.size >= 2) out += Feature.fromGeometry(LineString.fromLngLats(pts)).apply { addNumberProperty("s", t.vertices[start].surface) }
                    start = i
                }
            }
            out
        }
        src(SRC_SURFACE)?.setGeoJson(FeatureCollection.fromFeatures(surf))
    }

    fun setRidden(track: ActiveTrack?, alongM: Double) {
        val t = track ?: return src(SRC_RIDDEN)?.setGeoJson(FeatureCollection.fromFeatures(emptyList())) ?: Unit
        val idx = t.indexAt(alongM)
        if (idx < 1) return
        val p = t.pointAt(alongM)
        val pts = t.vertices.take(idx + 1).map { Point.fromLngLat(it.lon, it.lat) } + Point.fromLngLat(p.lon, p.lat)
        src(SRC_RIDDEN)?.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(pts)))
    }

    fun setAllTracks(lines: List<List<LatLon>>) {
        src(SRC_ALL_TRACKS)?.setGeoJson(FeatureCollection.fromFeatures(lines.filter { it.size >= 2 }.map { l -> Feature.fromGeometry(LineString.fromLngLats(l.map { Point.fromLngLat(it.lon, it.lat) })) }))
    }

    fun setAux(points: List<LatLon>?) {
        src(SRC_AUX)?.setGeoJson(
            if (points == null || points.size < 2) FeatureCollection.fromFeatures(emptyList())
            else FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) })))),
        )
    }

    fun setHazards(pins: List<HazardPinEntity>, visible: Boolean) {
        val f = if (!visible) emptyList() else pins.map {
            Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)).apply {
                addStringProperty("img", if (it.type == "SPERRUNG" || it.type == "ERDRUTSCH") IMG_HAZARD_RED else IMG_HAZARD_YELLOW)
            }
        }
        src(SRC_HAZARD)?.setGeoJson(FeatureCollection.fromFeatures(f))
    }

    fun setPois(pois: List<Poi>, visible: Boolean) {
        val f = if (!visible) emptyList() else pois.map {
            Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)).apply { addStringProperty("c", it.category.id); addStringProperty("n", it.name) }
        }
        src(SRC_POI)?.setGeoJson(FeatureCollection.fromFeatures(f))
    }

    /** Live berechnete Höhenlinien anzeigen (jede 5. Linie als Index-Linie mit Beschriftung). */
    fun setContours(lines: List<de.tetnavigator.map.ContourLine>, interval: Int) {
        val f = lines.map { l ->
            Feature.fromGeometry(LineString.fromLngLats(l.lon.indices.map { Point.fromLngLat(l.lon[it], l.lat[it]) })).apply {
                addNumberProperty("ele", l.elevation)
                addBooleanProperty("index", l.elevation % (interval * 5) == 0)
            }
        }
        src(SRC_CONTOURS)?.setGeoJson(FeatureCollection.fromFeatures(f))
    }

    /** Sichtbarer Ausschnitt (minLon, minLat, maxLon, maxLat) und Zoom. */
    fun visibleRegion(): Pair<DoubleArray, Double> {
        val b = map.projection.visibleRegion.latLngBounds
        return doubleArrayOf(b.longitudeWest, b.latitudeSouth, b.longitudeEast, b.latitudeNorth) to map.cameraPosition.zoom
    }

    fun onCameraIdle(block: () -> Unit) = map.addOnCameraIdleListener { block() }

    fun setPosition(lat: Double, lon: Double, bearing: Float) {
        src(SRC_POS)?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(lon, lat)).apply { addNumberProperty("b", bearing) })
    }

    fun followCamera(lat: Double, lon: Double, bearing: Double, zoom: Double, headingUp: Boolean) {
        if (!follow) return
        val pos = CameraPosition.Builder()
            .target(LatLng(lat, lon)).zoom(zoom)
            .bearing(if (headingUp) bearing else 0.0)
            .padding(0.0, 0.0, 0.0, 260.0 * density) // Position ins untere Drittel
            .tilt(0.0).build()
        map.easeCamera(CameraUpdateFactory.newCameraPosition(pos), 900)
    }

    fun overview(points: List<LatLon>) {
        if (points.size < 2) return
        follow = false
        val b = LatLngBounds.Builder().apply { points.forEach { include(LatLng(it.lat, it.lon)) } }.build()
        map.easeCamera(CameraUpdateFactory.newLatLngBounds(b, (60 * density).toInt()), 800)
    }

    fun zoomBy(delta: Double) {
        map.easeCamera(CameraUpdateFactory.zoomBy(delta), 300)
    }

    fun setGestures(enabled: Boolean) {
        map.uiSettings.apply {
            isScrollGesturesEnabled = enabled
            isRotateGesturesEnabled = enabled
            isTiltGesturesEnabled = false
            isZoomGesturesEnabled = enabled
            isDoubleTapGesturesEnabled = true
            isAttributionEnabled = false // eigene, immer sichtbare OSM-Attribution
            isLogoEnabled = false
            isCompassEnabled = false
        }
    }

    private fun addImages(s: Style, c: TetColors) {
        s.addImage(IMG_ARROW, arrow(c.accent.toArgb(), c.bg.toArgb()))
        s.addImage(IMG_HAZARD_YELLOW, triangle(c.warning.toArgb(), c.bg.toArgb()))
        s.addImage(IMG_HAZARD_RED, triangle(c.danger.toArgb(), c.bg.toArgb()))
        val poiColors = mapOf("campsite" to c.moss, "hut" to c.moss, "water" to androidx.compose.ui.graphics.Color(0xFF4A90B8), "supermarket" to c.sand, "workshop" to c.sand, "fuel" to c.warning)
        for ((id, col) in poiColors) s.addImage("poi-$id", dot(col.toArgb(), c.bg.toArgb(), id.first().uppercaseChar()))
    }

    private fun px(dp: Float) = (dp * density).toInt().coerceAtLeast(1)

    private fun arrow(fill: Int, stroke: Int): Bitmap {
        val n = px(34f); val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888); val cv = Canvas(b)
        val p = Path().apply { moveTo(n / 2f, n * 0.06f); lineTo(n * 0.86f, n * 0.9f); lineTo(n / 2f, n * 0.7f); lineTo(n * 0.14f, n * 0.9f); close() }
        cv.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
        cv.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = stroke; style = Paint.Style.STROKE; strokeWidth = 2f * density })
        return b
    }

    private fun triangle(fill: Int, stroke: Int): Bitmap {
        val n = px(26f); val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888); val cv = Canvas(b)
        val p = Path().apply { moveTo(n / 2f, n * 0.08f); lineTo(n * 0.94f, n * 0.9f); lineTo(n * 0.06f, n * 0.9f); close() }
        cv.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
        cv.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = stroke; style = Paint.Style.STROKE; strokeWidth = 2f * density })
        cv.drawText("!", n / 2f, n * 0.8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = stroke; textSize = n * 0.5f; textAlign = Paint.Align.CENTER; isFakeBoldText = true })
        return b
    }

    private fun dot(fill: Int, stroke: Int, letter: Char): Bitmap {
        val n = px(22f); val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888); val cv = Canvas(b)
        cv.drawCircle(n / 2f, n / 2f, n / 2f - density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
        cv.drawCircle(n / 2f, n / 2f, n / 2f - density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = stroke; style = Paint.Style.STROKE; strokeWidth = 1.5f * density })
        cv.drawText(letter.toString(), n / 2f, n * 0.68f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = stroke; textSize = n * 0.55f; textAlign = Paint.Align.CENTER; isFakeBoldText = true })
        return b
    }

    private fun runs(idx: List<Int>, pred: (Int) -> Boolean): List<List<Int>> {
        val out = ArrayList<List<Int>>(); var cur = ArrayList<Int>()
        for (i in idx) if (pred(i)) cur += i else { if (cur.size > 1) out += cur; cur = ArrayList() }
        if (cur.size > 1) out += cur
        return out
    }

    companion object {
        const val SRC_TRACK = "tet-track"
        const val SRC_RIDDEN = "tet-ridden"
        const val SRC_CHANGED = "tet-changed"
        const val SRC_SURFACE = "tet-surface"
        const val SRC_AUX = "tet-aux"
        const val SRC_HAZARD = "tet-hazard"
        const val SRC_POI = "tet-poi"
        const val SRC_POS = "tet-pos"
        const val SRC_ALL_TRACKS = "tet-all"
        const val SRC_CONTOURS = "tet-contours"
        const val IMG_ARROW = "pos-arrow"
        const val IMG_HAZARD_YELLOW = "hazard-y"
        const val IMG_HAZARD_RED = "hazard-r"

    }
}
