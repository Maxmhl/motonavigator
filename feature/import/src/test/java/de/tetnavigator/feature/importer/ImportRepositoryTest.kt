package de.tetnavigator.feature.importer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import de.tetnavigator.core.database.ProgressEntity
import de.tetnavigator.core.database.SpatialIndex
import de.tetnavigator.core.database.TetDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportRepositoryTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = TetDatabase.build(ctx, inMemory = true)
    private val spatial = SpatialIndex(db)
    private val repo = ImportRepository(ctx, db, spatial)

    @After fun close() = db.close()

    private fun gpx(name: String, offsetFrom: Int = -1, offsetTo: Int = -1): String {
        val sb = StringBuilder("<?xml version=\"1.0\"?><gpx version=\"1.1\"><metadata><time>2024-05-01T00:00:00Z</time></metadata>")
        sb.append("<wpt lat=\"60.01\" lon=\"10.0001\"><name>Tor</name></wpt>")
        for (s in 0 until 2) {
            sb.append("<trk><name>$name $s</name><trkseg>")
            for (i in 0 until 200) {
                val lon = 10.0 + s * 0.1 + if (i in offsetFrom..offsetTo && s == 0) 0.003 else 0.0
                sb.append("<trkpt lat=\"${60.0 + i * 0.0005}\" lon=\"$lon\"><ele>${100 + i}</ele></trkpt>")
            }
            sb.append("</trkseg></trk>")
        }
        return sb.append("</gpx>").toString()
    }

    private fun file(name: String, content: String) = File(ctx.cacheDir, name).apply { writeText(content) }

    @Test fun importsGpxIntoLibrary() = runTest {
        val staged = repo.stageFile(file("a.gpx", gpx("NOR")), "TET_Norway_2024.gpx")
        val pv = staged.files.single()
        assertThat(pv.detectedCountry).isEqualTo("NO")
        assertThat(pv.sections).hasSize(2)
        assertThat(pv.waypointCount).isEqualTo(1)
        assertThat(pv.olderVersion).isNull()

        val res = repo.commit(staged)
        assertThat(res.sections).isEqualTo(2)
        val summary = db.trackDao().observeCountrySummaries().first().single()
        assertThat(summary.countryCode).isEqualTo("NO")
        assertThat(summary.sectionCount).isEqualTo(2)
        assertThat(summary.totalLengthM).isWithin(500.0).of(2 * 11_100.0)
        val wp = db.trackDao().waypointsOfFile(res.fileIds.single()).single()
        assertThat(wp.sectionId).isNotNull()

        // räumlicher Index findet nächsten Trackpunkt
        val hit = spatial.nearest(60.02, 10.0005)!!
        assertThat(hit.distanceM).isLessThan(50.0)
    }

    @Test fun duplicateIsSkipped() = runTest {
        repo.commit(repo.stageFile(file("a.gpx", gpx("NOR")), "TET_Norway.gpx"))
        val again = repo.stageFile(file("b.gpx", gpx("NOR")), "TET_Norway.gpx")
        assertThat(again.files.single().alreadyImported).isTrue()
        assertThat(repo.commit(again).skippedDuplicates).isEqualTo(1)
    }

    @Test fun updateMarksChangesAndKeepsProgress() = runTest {
        repo.commit(repo.stageFile(file("a.gpx", gpx("NOR")), "TET_Norway_2024-05-01.gpx"))
        val oldSection = db.trackDao().allSections().first { it.name == "NOR 0" }
        db.progressDao().upsert(ProgressEntity(oldSection.id, false, 5_000.0, 0))

        val staged = repo.stageFile(file("b.gpx", gpx("NOR", 120, 150)), "TET_Norway_2025-01-01.gpx")
        val pv = staged.files.single()
        assertThat(pv.olderVersion).isNotNull()
        assertThat(pv.olderVersion!!.changedSegments).isEqualTo(1)
        assertThat(pv.changedPolylines).isNotEmpty()

        repo.commit(staged)
        val sections = db.trackDao().allSections()
        assertThat(sections).hasSize(2) // alte Version ersetzt
        val newS = sections.first { it.name == "NOR 0" }
        assertThat(newS.changedRanges).isNotEmpty()
        val prog = db.progressDao().get(newS.id)!!
        assertThat(prog.rideDistanceM).isWithin(100.0).of(5_000.0)
        assertThat(db.trackDao().points(newS.id).count { it.changed }).isGreaterThan(20)
    }

    @Test fun importsZipWithMultipleGpx() = runTest {
        val zip = File(ctx.cacheDir, "tet.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("TET/Sweden.gpx")); z.write(gpx("SWE").toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("__MACOSX/._Sweden.gpx")); z.write(byteArrayOf(0)); z.closeEntry()
            z.putNextEntry(ZipEntry("TET/Denmark.gpx")); z.write(gpx("DNK").replace("lon=\"10.", "lon=\"9.").toByteArray()); z.closeEntry()
        }
        val staged = repo.stageFile(zip, "tet.zip")
        assertThat(staged.isZip).isTrue()
        assertThat(staged.files.map { it.detectedCountry }).containsExactly("SE", "DK")
        val res = repo.commit(staged)
        assertThat(res.files).isEqualTo(2)
        assertThat(db.trackDao().observeCountrySummaries().first().map { it.countryCode }).containsExactly("DK", "SE")
    }
}
