package de.tetnavigator.feature.navigation

import com.google.common.truth.Truth.assertThat
import de.tetnavigator.core.common.LatLon
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Läuft nur mit echten Segmenten: ./gradlew :feature:navigation:testDebugUnitTest -PtetSegmentsDir=/pfad
 * (benötigt E10_N60.rd5, Raum Røros).
 */
class BRouterIntegrationTest {
    private val segments = System.getProperty("tet.segments")?.let(::File)
    private fun profile(name: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "tet-brouter-profiles").apply { mkdirs() }
        for (n in listOf("lookups.dat", name)) {
            val src = listOf(File("src/main/assets/brouter/$n"), File("feature/navigation/src/main/assets/brouter/$n")).first { it.exists() }
            src.copyTo(File(dir, n), overwrite = true)
        }
        return File(dir, name)
    }

    @Test fun routesOfflineWithGravelProfile() {
        assumeTrue(segments?.resolve("E10_N60.rd5")?.exists() == true)
        val (route, pts) = BRouterEngine.route(segments!!, profile(OfflineRouter.PROFILE), listOf(LatLon(62.5745, 11.3850), LatLon(62.6300, 11.2600)))
        println("Route: ${route.distanceM} m, ${route.points.size} Punkte, ${route.seconds} s")
        assertThat(route.distanceM).isGreaterThan(5_000.0)
        assertThat(route.distanceM).isLessThan(40_000.0)
        assertThat(pts.first().lat).isWithin(0.01).of(62.5745)
    }

    @Test fun matchReturnsWayTagsWithSurface() {
        assumeTrue(segments?.resolve("E10_N60.rd5")?.exists() == true)
        val wps = listOf(LatLon(62.5745, 11.3850), LatLon(62.6000, 11.3300), LatLon(62.6300, 11.2600))
        val pts = BRouterEngine.route(segments!!, profile(OfflineRouter.MATCH_PROFILE), wps).second
        val highways = pts.mapNotNull { it.wayTags["highway"] }.toSet()
        val surfaces = pts.mapNotNull { it.wayTags["surface"] }.toSet()
        println("highway=$highways surface=$surfaces tracktype=${pts.mapNotNull { it.wayTags["tracktype"] }.toSet()}")
        assertThat(highways).isNotEmpty()
        assertThat(pts.count { it.wayTags.isNotEmpty() }).isGreaterThan(pts.size / 2)
    }
}
