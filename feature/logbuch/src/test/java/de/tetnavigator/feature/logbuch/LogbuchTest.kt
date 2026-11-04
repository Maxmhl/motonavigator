package de.tetnavigator.feature.logbuch

import com.google.common.truth.Truth.assertThat
import de.tetnavigator.core.database.HazardPinEntity
import de.tetnavigator.core.database.JournalEntryEntity
import de.tetnavigator.core.database.RideEntity
import de.tetnavigator.core.database.RidePointEntity
import org.junit.Test
import java.io.StringWriter

class RideStatsTest {
    @Test fun distanceOffroadAscentAndCountries() {
        val s = RideStats()
        var t = 0L
        // 100 Punkte nach Norden, ~11 m Abstand, 1 s Takt, steigend; Hälfte offroad
        for (i in 0 until 100) {
            s.add(60.0 + i * 0.0001, 10.0, 100.0 + i * 0.5, t, 11f, 5f, offroad = i >= 50, country = "NO")
            t += 1000
        }
        assertThat(s.distanceM).isWithin(30.0).of(99 * 11.12)
        assertThat(s.offroadM / s.distanceM).isWithin(0.03).of(0.5)
        assertThat(s.ascentM).isWithin(5.0).of(49.5)
        assertThat(s.perCountry["NO"]!![0]).isWithin(0.1).of(s.distanceM)
        assertThat(s.movingMs).isEqualTo(99_000)
    }

    @Test fun rejectsInaccurateAndJumpingFixes() {
        val s = RideStats()
        s.add(60.0, 10.0, null, 0, 10f, 5f, false, null)
        assertThat(s.add(60.001, 10.0, null, 1000, 10f, 80f, false, null)).isFalse() // ungenau
        assertThat(s.add(61.0, 10.0, null, 2000, 10f, 5f, false, null)).isFalse()   // 111 km in 2 s
        assertThat(s.add(60.00001, 10.0, null, 3000, 0f, 5f, false, null)).isFalse() // < 3 m
        assertThat(s.distanceM).isEqualTo(0.0)
    }
}

class GpxWriterTest {
    @Test fun writesValidGpxWithEscaping() {
        val w = StringWriter()
        val ride = RideEntity(1, 0, 1000, 10.0, 0.0, 0.0, title = "Tour <Nord> & Süd")
        GpxWriter.write(
            w, "Logbuch",
            listOf(ride to listOf(RidePointEntity(1, 0, 60.0, 10.0, 100.0, 0, 0f, "NO", false), RidePointEntity(1, 1, 60.001, 10.0, null, 1000, 0f, "NO", false))),
            listOf(JournalEntryEntity(1, 1, 60.0, 10.0, "Brücke weg", null, 0)),
            listOf(HazardPinEntity(1, 60.0, 10.0, "FURT", "tief", null, 0)),
        )
        val x = w.toString()
        assertThat(x).contains("<trkpt lat=\"60.0\" lon=\"10.0\"><ele>100.0</ele>")
        assertThat(x).contains("Tour &lt;Nord&gt; &amp; Süd")
        assertThat(x).contains("<name>FURT</name><desc>tief</desc>")
        assertThat(x).contains("<desc>Brücke weg</desc>")
        // Wohlgeformt?
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(x.byteInputStream())
    }
}
