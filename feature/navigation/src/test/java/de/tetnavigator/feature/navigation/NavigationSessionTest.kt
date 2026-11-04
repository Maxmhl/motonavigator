package de.tetnavigator.feature.navigation

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import de.tetnavigator.core.common.Geo
import de.tetnavigator.core.common.GeoPoint
import de.tetnavigator.core.database.SectionEntity
import de.tetnavigator.core.database.SpatialIndex
import de.tetnavigator.core.database.TetDatabase
import de.tetnavigator.core.database.TrackFileEntity
import de.tetnavigator.core.database.TrackPointEntity
import de.tetnavigator.core.offlinedata.OfflineStorage
import de.tetnavigator.core.preferences.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NavigationSessionTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = TetDatabase.build(ctx, inMemory = true)
    private val settings = SettingsRepository(
        PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
            File(ctx.cacheDir, "nav-test-${System.nanoTime()}.preferences_pb")
        },
    )
    private val storage = OfflineStorage(ctx)
    private val session = NavigationSession(db, SpatialIndex(db), settings, OfflineRouter(ctx, storage))

    @After fun close() = db.close()

    private suspend fun insertSection(): Pair<Long, List<GeoPoint>> {
        val pts = (0..400).map { GeoPoint(60.0 + it * 0.0001, 10.0, 100.0 + it) } // ~4,4 km Nord
        val cum = Geo.cumulative(pts)
        val dao = db.trackDao()
        val f = dao.insertFile(TrackFileEntity(countryCode = "NO", fileName = "t.gpx", importedAt = 0, fileDate = 0, sha256 = "x"))
        val id = dao.insertSection(SectionEntity(trackFileId = f, countryCode = "NO", name = "S1", lengthM = cum.last(), ascentM = 400.0, descentM = 0.0, orderIndex = 0, pointCount = pts.size, minLat = 60.0, minLon = 10.0, maxLat = 60.04, maxLon = 10.0, maxEle = 500.0, maxEleDistM = cum.last()))
        dao.insertPoints(pts.mapIndexed { i, p -> TrackPointEntity(id, i, p.lat, p.lon, p.ele, cum[i]) })
        return id to pts
    }

    private suspend fun settle() { delay(50) }

    @Test fun ridesSectionSavesProgressAndRaisesAlarm() = runBlocking {
        val (id, pts) = insertSection()
        session.start(id, reversed = false)
        assertThat(settings.current().activeSectionId).isEqualTo(id)

        val events = ArrayList<NavEvent>()
        val job = CoroutineScope(Dispatchers.Default).launch { session.events.collect { events += it } }

        // Fahrt über die ersten 3 km
        var t = 0L
        for (i in 0..270 step 3) {
            session.onFix(Fix(pts[i].lat, pts[i].lon, t, 10f, 0f, 5f, null)); t += 3000
            settle()
        }
        val st = withTimeout(5_000) { session.state.first { it != null && it.match!!.alongM > 2_900 } }!!
        assertThat(st.remainingM).isWithin(60.0).of(Geo.polylineLength(pts) - Geo.polylineLength(pts.take(271)))
        val prog = db.progressDao().get(id)!!
        assertThat(prog.rideDistanceM).isAtLeast(2_800.0)
        assertThat(prog.ridden).isFalse()

        // 150 m östlich daneben für 8 s → Abweich-Alarm
        for (k in 0..8) {
            session.onFix(Fix(pts[272].lat, pts[272].lon + 0.0027, t, 5f, 0f, 5f, null)); t += 1000
            settle()
        }
        withTimeout(5_000) { while (events.none { it == NavEvent.DeviationAlarm }) delay(20) }
        assertThat(session.state.value!!.deviation).isEqualTo(DeviationMonitor.State.OFF_TRACK)

        // zurück und bis zum Ende → Sektion gefahren
        for (i in 272..400 step 3) { session.onFix(Fix(pts[i].lat, pts[i].lon, t, 10f, 0f, 5f, null)); t += 3000; settle() }
        session.onFix(Fix(pts[400].lat, pts[400].lon, t, 10f, 0f, 5f, null)); settle()
        withTimeout(5_000) { while (db.progressDao().get(id)?.ridden != true) delay(20) }
        assertThat(events).contains(NavEvent.BackOnTrack)
        job.cancel()
    }

    @Test fun reverseStartsFromOtherEnd() = runBlocking {
        val (id, pts) = insertSection()
        session.start(id, reversed = true)
        val t = session.track.value!!
        assertThat(t.reversed).isTrue()
        session.onFix(Fix(pts.last().lat, pts.last().lon, 0, 5f, 180f, 5f, null))
        val st = withTimeout(5_000) { session.state.first { it != null } }!!
        assertThat(st.match!!.alongM).isLessThan(20.0)
        session.reverse()
        assertThat(session.track.value!!.reversed).isFalse()
    }
}
