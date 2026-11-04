package de.tetnavigator.feature.importer

import com.google.common.truth.Truth.assertThat
import de.tetnavigator.core.common.Countries
import de.tetnavigator.core.common.Geo
import de.tetnavigator.core.common.GeoPoint
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.InputStream
import java.io.SequenceInputStream

class GpxParserTest {
    private val parser = GpxParser { KXmlParser() }

    private fun res(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!

    @Test fun parsesTracksWaypointsAndMetadata() {
        val tracks = ArrayList<GpxTrack>(); val wpts = ArrayList<GpxWaypoint>(); var meta: GpxMetadata? = null
        parser.parse(res("tet_norway_sample.gpx"), object : GpxListener {
            override fun onMetadata(meta2: GpxMetadata) { meta = meta2 }
            override fun onTrack(track: GpxTrack) { tracks += track }
            override fun onWaypoint(wpt: GpxWaypoint) { wpts += wpt }
        })
        assertThat(tracks.map { it.name }).containsExactly("NOR Sektion 01", "NOR Sektion 02").inOrder()
        assertThat(tracks[0].points).hasSize(5)
        assertThat(tracks[1].points).hasSize(6) // zwei trkseg verbunden
        assertThat(tracks[0].points[0].ele).isEqualTo(120.0)
        assertThat(wpts.single().name).isEqualTo("Furt")
        assertThat(meta!!.time).isEqualTo(GpxParser.parseTime("2024-05-01T10:00:00Z"))
    }

    @Test fun parsesRoutesAndNamespacedGpx10() {
        val xml = """<?xml version="1.0"?>
            <gpx:gpx version="1.0" xmlns:gpx="http://www.topografix.com/GPX/1/0">
              <gpx:time>2023-03-01T08:00:00Z</gpx:time>
              <gpx:rte><gpx:name>Route A</gpx:name>
                <gpx:rtept lat="59.0" lon="10.0"/><gpx:rtept lat="59.01" lon="10.0"/>
              </gpx:rte>
            </gpx:gpx>"""
        val items = ArrayList<GpxItem>()
        val r = parser.reader(xml.byteInputStream())
        while (true) items += r.next() ?: break
        val t = items.filterIsInstance<GpxItem.Track>().single().track
        assertThat(t.isRoute).isTrue()
        assertThat(t.name).isEqualTo("Route A")
        assertThat(items.filterIsInstance<GpxItem.Meta>()).isNotEmpty()
    }

    @Test(expected = GpxParseException::class)
    fun rejectsNonGpx() {
        parser.parse("<html><body/></html>".byteInputStream(), object : GpxListener {})
    }

    /**
     * Streaming mit großer Datei: ~60 MB GPX (40 Sektionen à 25.000 Punkte) wird
     * erzeugt, ohne sie je komplett im Speicher zu halten, und Track für Track gelesen.
     */
    @Test fun streamsLargeFileTrackByTrack() {
        val sections = 40; val perSection = 25_000
        val rt = Runtime.getRuntime()
        var maxHeldPoints = 0
        var total = 0
        parser.parse(LargeGpx.stream(sections, perSection), object : GpxListener {
            override fun onTrack(track: GpxTrack) {
                maxHeldPoints = maxOf(maxHeldPoints, track.points.size)
                total += track.points.size
            }
        })
        assertThat(total).isEqualTo(sections * perSection)
        assertThat(maxHeldPoints).isEqualTo(perSection)
        assertThat(rt.totalMemory() - rt.freeMemory()).isLessThan(1_500_000_000L)
    }

    @Test fun detectsCountry() {
        assertThat(Countries.detectFromText("TET_Norway_2024-05-01.gpx")!!.code).isEqualTo("NO")
        assertThat(Countries.detectFromText("tet-sverige.gpx")!!.code).isEqualTo("SE")
        assertThat(Countries.detectFromText("TET Deutschland Sektion 3")!!.code).isEqualTo("DE")
        assertThat(Countries.detectFromPoints(sequenceOf(de.tetnavigator.core.common.LatLon(55.7, 12.5)))!!.code).isEqualTo("DK")
    }

    @Test fun ageWarning() {
        val day = 86_400_000L
        assertThat(GpxAge.isOld(0, 60, now = 61 * day)).isTrue()
        assertThat(GpxAge.isOld(0, 60, now = 59 * day)).isFalse()
    }

    @Test fun fileStemIgnoresDates() {
        assertThat(FileNames.stem("TET_Norway_2024-05-01.gpx")).isEqualTo(FileNames.stem("TET_Norway_2025-02-11.gpx"))
        assertThat(FileNames.stem("TET_Norway_v2.gpx")).isEqualTo(FileNames.stem("TET_Norway.gpx"))
    }
}

/** Erzeugt eine große GPX-Datei als Stream, ohne sie im Speicher aufzubauen. */
object LargeGpx {
    fun stream(sections: Int, perSection: Int): InputStream {
        val parts = sequence {
            yield("<?xml version=\"1.0\"?><gpx version=\"1.1\" creator=\"test\">".byteInputStream())
            for (s in 0 until sections) {
                yield("<trk><name>Sektion $s</name><trkseg>".byteInputStream())
                var i = 0
                while (i < perSection) {
                    val sb = StringBuilder()
                    repeat(minOf(1000, perSection - i)) {
                        val lat = 59.0 + s * 0.1 + i * 0.00001
                        sb.append("<trkpt lat=\"").append(lat).append("\" lon=\"10.0\"><ele>").append(100 + i % 50).append("</ele></trkpt>")
                        i++
                    }
                    yield(sb.toString().byteInputStream())
                }
                yield("</trkseg></trk>".byteInputStream())
            }
            yield("</gpx>".byteInputStream())
        }
        val it = parts.iterator()
        return SequenceInputStream(object : java.util.Enumeration<InputStream> {
            override fun hasMoreElements() = it.hasNext()
            override fun nextElement() = it.next()
        })
    }
}

class TrackDiffTest {
    private fun line(n: Int, lat0: Double = 60.0, offsetFrom: Int = -1, offsetTo: Int = -1, offset: Double = 0.0) =
        (0 until n).map { i ->
            val o = if (i in offsetFrom..offsetTo) offset else 0.0
            GeoPoint(lat0 + i * 0.0005, 10.0 + o) // ~55 m Abstand
        }

    @Test fun identicalTracksHaveNoChanges() {
        val r = TrackDiff().compare(line(100), line(100))
        assertThat(r.changedCount).isEqualTo(0)
    }

    @Test fun detectsRerouteAsOneChangedSegment() {
        val old = line(200)
        val new = line(200, offsetFrom = 80, offsetTo = 110, offset = 0.003) // ~170 m daneben
        val r = TrackDiff().compare(old, new)
        assertThat(r.changedCount).isEqualTo(1)
        val (a, b) = r.ranges.single()
        val cum = Geo.cumulative(new)
        assertThat(a).isAtMost(cum[80])
        assertThat(b).isAtLeast(cum[110])
        assertThat(r.changed[95]).isTrue()
        assertThat(r.changed[10]).isFalse()
    }

    @Test fun twoReroutesAreCountedSeparately() {
        val old = line(300)
        val new = line(300, offsetFrom = 50, offsetTo = 60, offset = 0.002).toMutableList()
        for (i in 200..220) new[i] = new[i].copy(lon = 10.002)
        assertThat(TrackDiff().compare(old, new).changedCount).isEqualTo(2)
    }

    @Test fun rangesRoundTrip() {
        val r = listOf(10.0 to 200.0, 5000.0 to 5600.0)
        assertThat(TrackDiff.decodeRanges(TrackDiff.encodeRanges(r))).isEqualTo(r)
    }
}
