package de.tetnavigator.feature.importer

import de.tetnavigator.core.common.GeoPoint
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.time.Instant
import java.time.OffsetDateTime

data class GpxMetadata(val name: String?, val desc: String?, val time: Long?)

/** Ein <trk> (alle <trkseg> verbunden) oder eine <rte>. */
data class GpxTrack(
    val name: String,
    val desc: String?,
    val points: List<GeoPoint>,
    val isRoute: Boolean,
)

data class GpxWaypoint(val lat: Double, val lon: Double, val name: String, val type: String, val ele: Double?)

sealed interface GpxItem {
    data class Meta(val meta: GpxMetadata) : GpxItem
    data class Track(val track: GpxTrack) : GpxItem
    data class Waypoint(val wpt: GpxWaypoint) : GpxItem
}

interface GpxListener {
    fun onMetadata(meta: GpxMetadata) {}
    /** Wird nach jedem vollständigen Track aufgerufen – nur dieser Track liegt im Speicher. */
    fun onTrack(track: GpxTrack) {}
    fun onWaypoint(wpt: GpxWaypoint) {}
}

class GpxParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Streaming-GPX-Parser auf Basis von XmlPullParser (Spezifikation 6):
 * Die Datei wird nie komplett geladen; Tracks werden einzeln an den Listener
 * übergeben. Unterstützt GPX 1.0/1.1, <trk>/<trkseg>/<trkpt>, <rte>/<rtept>, <wpt>.
 */
class GpxParser(private val factory: () -> XmlPullParser = ::defaultParser) {

    fun parse(input: InputStream, listener: GpxListener) {
        val r = reader(input)
        while (true) {
            when (val item = r.next() ?: break) {
                is GpxItem.Meta -> listener.onMetadata(item.meta)
                is GpxItem.Track -> listener.onTrack(item.track)
                is GpxItem.Waypoint -> listener.onWaypoint(item.wpt)
            }
        }
    }

    /** Pull-API: liefert Metadaten, Tracks und Wegpunkte einzeln nacheinander. */
    fun reader(input: InputStream): GpxReader {
        val p = factory()
        try {
            p.setInput(input, null)
        } catch (e: XmlPullParserException) {
            throw GpxParseException("GPX fehlerhaft: ${e.message}", e)
        }
        return GpxReader(p)
    }

    inner class GpxReader internal constructor(private val p: XmlPullParser) {
        private var sawGpx = false
        private var done = false

        fun next(): GpxItem? {
            if (done) return null
            try {
                var event = p.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    var item: GpxItem? = null
                    if (event == XmlPullParser.START_TAG) {
                        when (p.name.local()) {
                            "gpx" -> sawGpx = true
                            "metadata" -> item = GpxItem.Meta(readMetadata(p))
                            "trk" -> item = readTrack(p, "trk", "trkpt")?.let { GpxItem.Track(it) }
                            "rte" -> item = readTrack(p, "rte", "rtept")?.let { GpxItem.Track(it) }
                            "wpt" -> item = readWaypoint(p)?.let { GpxItem.Waypoint(it) }
                            // GPX 1.0: <time> direkt unter <gpx>
                            "time" -> if (p.depth == 2) item = GpxItem.Meta(GpxMetadata(null, null, parseTime(p.nextText())))
                        }
                    }
                    p.next()
                    if (item != null) return item
                    event = p.eventType
                }
                done = true
                if (!sawGpx) throw GpxParseException("Keine GPX-Datei")
                return null
            } catch (e: XmlPullParserException) {
                throw GpxParseException("GPX fehlerhaft: ${e.message}", e)
            }
        }
    }

    private fun readMetadata(p: XmlPullParser): GpxMetadata {
        var name: String? = null; var desc: String? = null; var time: Long? = null
        val depth = p.depth
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType == XmlPullParser.END_DOCUMENT) break
            if (p.eventType != XmlPullParser.START_TAG || p.depth != depth + 1) continue
            when (p.name.local()) {
                "name" -> name = p.nextText().trim()
                "desc" -> desc = p.nextText().trim()
                "time" -> time = parseTime(p.nextText())
            }
        }
        return GpxMetadata(name, desc, time)
    }

    private fun readTrack(p: XmlPullParser, tag: String, ptTag: String): GpxTrack? {
        var name: String? = null; var desc: String? = null
        val points = ArrayList<GeoPoint>(1024)
        val depth = p.depth
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType == XmlPullParser.END_DOCUMENT) break
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.name.local()) {
                "name" -> if (p.depth == depth + 1) name = p.nextText().trim()
                "desc" -> if (p.depth == depth + 1) desc = p.nextText().trim()
                ptTag -> readPoint(p)?.let(points::add)
            }
        }
        if (points.size < 2) return null
        return GpxTrack(name?.takeIf { it.isNotBlank() } ?: "Sektion", desc, points, tag == "rte")
    }

    private fun readPoint(p: XmlPullParser): GeoPoint? {
        val lat = p.getAttributeValue(null, "lat")?.toDoubleOrNull()
        val lon = p.getAttributeValue(null, "lon")?.toDoubleOrNull()
        var ele: Double? = null; var time: Long? = null
        val depth = p.depth
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType == XmlPullParser.END_DOCUMENT) break
            if (p.eventType != XmlPullParser.START_TAG || p.depth != depth + 1) continue
            when (p.name.local()) {
                "ele" -> ele = p.nextText().trim().toDoubleOrNull()
                "time" -> time = parseTime(p.nextText())
                else -> skip(p)
            }
        }
        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return GeoPoint(lat, lon, ele, time)
    }

    private fun readWaypoint(p: XmlPullParser): GpxWaypoint? {
        val lat = p.getAttributeValue(null, "lat")?.toDoubleOrNull()
        val lon = p.getAttributeValue(null, "lon")?.toDoubleOrNull()
        var name = ""; var type = ""; var sym = ""; var ele: Double? = null
        val depth = p.depth
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType == XmlPullParser.END_DOCUMENT) break
            if (p.eventType != XmlPullParser.START_TAG || p.depth != depth + 1) continue
            when (p.name.local()) {
                "name" -> name = p.nextText().trim()
                "type" -> type = p.nextText().trim()
                "sym" -> sym = p.nextText().trim()
                "ele" -> ele = p.nextText().trim().toDoubleOrNull()
                else -> skip(p)
            }
        }
        if (lat == null || lon == null) return null
        return GpxWaypoint(lat, lon, name, type.ifBlank { sym }, ele)
    }

    private fun skip(p: XmlPullParser) {
        if (p.eventType != XmlPullParser.START_TAG) return
        var d = 1
        while (d > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> d++
                XmlPullParser.END_TAG -> d--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private fun String.local() = substringAfter(':')

    companion object {
        fun defaultParser(): XmlPullParser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser()

        fun parseTime(s: String?): Long? {
            val t = s?.trim().orEmpty()
            if (t.isEmpty()) return null
            return try {
                Instant.parse(t).toEpochMilli()
            } catch (_: Exception) {
                try { OffsetDateTime.parse(t).toInstant().toEpochMilli() } catch (_: Exception) {
                    try { Instant.parse(t + "Z").toEpochMilli() } catch (_: Exception) { null }
                }
            }
        }
    }
}
