package de.tetnavigator.feature.navigation

import com.google.common.truth.Truth.assertThat
import de.tetnavigator.core.common.Geo
import de.tetnavigator.core.common.GeoPoint
import org.junit.Test

private fun vertices(pts: List<GeoPoint>, surface: (Int) -> Int = { 0 }): List<TrackVertex> {
    val cum = Geo.cumulative(pts)
    return pts.mapIndexed { i, p -> TrackVertex(p.lat, p.lon, p.ele, cum[i], surface(i), false) }
}

/** Gerade nach Norden, dann Kehre (180°) und zurück nach Süden, 30 m versetzt. */
private fun hairpinTrack(): List<GeoPoint> {
    val out = ArrayList<GeoPoint>()
    for (i in 0..40) out += GeoPoint(60.0 + i * 0.0001, 10.0)            // ~445 m Nord
    val c = Geo.destination(out.last().lat, out.last().lon, 90.0, 15.0)
    for (a in 0..12) out += Geo.destination(c.lat, c.lon, 270.0 + a * 15.0, 15.0).let { GeoPoint(it.lat, it.lon) }
    val east = out.last()
    for (i in 1..40) out += GeoPoint(east.lat - i * 0.0001, east.lon)     // zurück nach Süden
    return out
}

class CurveDetectorTest {
    @Test fun straightHasNoTurns() {
        val pts = (0..100).map { GeoPoint(60.0 + it * 0.0001, 10.0) }
        assertThat(CurveDetector().detect(vertices(pts))).isEmpty()
    }

    @Test fun detectsHairpinRight() {
        val turns = CurveDetector().detect(vertices(hairpinTrack()))
        assertThat(turns).hasSize(1)
        assertThat(turns[0].kind).isEqualTo(TurnKind.HAIRPIN)
        assertThat(turns[0].side).isEqualTo(TurnSide.RIGHT)
    }

    @Test fun detectsNinetyDegreeCurveLeft() {
        val pts = ArrayList<GeoPoint>()
        for (i in 0..30) pts += GeoPoint(60.0 + i * 0.0001, 10.0)
        val last = pts.last()
        for (i in 1..30) pts += GeoPoint(last.lat, last.lon - i * 0.0002)
        val turns = CurveDetector().detect(vertices(pts))
        assertThat(turns).hasSize(1)
        assertThat(turns[0].kind).isEqualTo(TurnKind.CURVE)
        assertThat(turns[0].side).isEqualTo(TurnSide.LEFT)
        assertThat(turns[0].distM).isWithin(30.0).of(Geo.cumulative(pts)[30])
    }
}

class DeviationMonitorTest {
    @Test fun alarmOnlyAfterFiveSeconds() {
        val m = DeviationMonitor(40.0)
        assertThat(m.update(60.0, 0)).isFalse()
        assertThat(m.update(60.0, 3_000)).isFalse()
        assertThat(m.state).isEqualTo(DeviationMonitor.State.SUSPECT)
        assertThat(m.update(60.0, 5_000)).isTrue()
        assertThat(m.state).isEqualTo(DeviationMonitor.State.OFF_TRACK)
        assertThat(m.update(60.0, 6_000)).isFalse() // nur einmal auslösen
    }

    @Test fun gpsJumpDoesNotAlarm() {
        val m = DeviationMonitor(40.0)
        m.update(80.0, 0); m.update(90.0, 2_000)
        m.update(10.0, 3_000) // zurück unter Schwelle → Zähler zurückgesetzt
        assertThat(m.update(80.0, 6_000)).isFalse()
        assertThat(m.state).isEqualTo(DeviationMonitor.State.SUSPECT)
    }

    @Test fun backOnTrackNeedsHalfThreshold() {
        val m = DeviationMonitor(40.0)
        m.update(100.0, 0); m.update(100.0, 6_000)
        m.update(30.0, 7_000)
        assertThat(m.state).isEqualTo(DeviationMonitor.State.OFF_TRACK)
        m.update(19.0, 8_000)
        assertThat(m.state).isEqualTo(DeviationMonitor.State.ON_TRACK)
    }
}

class TrackMatcherTest {
    @Test fun followsTrackThroughHairpinWithoutJumping() {
        val pts = hairpinTrack()
        val track = ActiveTrack.build(1, "T", "NO", false, vertices(pts))
        val matcher = TrackMatcher(track)
        val cum = Geo.cumulative(pts)
        var lastAlong = -1.0
        // Fahrt entlang des Tracks, 3 m seitlich versetzt
        for (i in pts.indices) {
            val p = pts[i]
            val r = matcher.match(p.lat, p.lon + 0.00003, i * 1000L, 8f, null)!!
            assertThat(r.alongM).isAtLeast(lastAlong - 1.0) // monoton, kein Sprung auf den Gegenast
            assertThat(r.alongM).isWithin(25.0).of(cum[i])
            lastAlong = r.alongM
        }
    }

    @Test fun reverseTrackStartsAtOldEnd() {
        val pts = hairpinTrack()
        val fwd = ActiveTrack.build(1, "T", "NO", false, vertices(pts) { it % 3 })
        val rev = ActiveTrack.build(1, "T", "NO", true, vertices(pts) { it % 3 })
        assertThat(rev.vertices.first().lat).isEqualTo(pts.last().lat)
        assertThat(rev.lengthM).isWithin(0.01).of(fwd.lengthM)
        assertThat(rev.vertices.first().distM).isEqualTo(0.0)
        // Segment-Untergrund bleibt dem gleichen geografischen Segment zugeordnet
        val n = pts.size
        assertThat(rev.vertices[0].surface).isEqualTo(fwd.vertices[n - 2].surface)
    }

    @Test fun engineReportsDeviationAndRemaining() {
        val pts = (0..200).map { GeoPoint(60.0 + it * 0.0001, 10.0) }
        val track = ActiveTrack.build(1, "T", "NO", false, vertices(pts))
        val e = NavigationEngine(track, 40.0)
        val s1 = e.update(Fix(60.005, 10.0, 0, 10f, 0f, 5f, null))
        assertThat(s1.deviation).isEqualTo(DeviationMonitor.State.ON_TRACK)
        assertThat(s1.remainingM).isWithin(20.0).of(track.lengthM * 0.75)
        // 100 m östlich daneben für 6 s
        var alarm = false
        for (t in 1..7) alarm = alarm || e.update(Fix(60.0051, 10.0018, t * 1000L, 10f, 0f, 5f, null)).alarmTriggered
        assertThat(alarm).isTrue()
        val s = e.update(Fix(60.0052, 10.0018, 8000, 10f, 0f, 5f, null))
        assertThat(s.bearingBack!!).isWithin(20.0).of(270.0)
    }
}
