package de.tetnavigator.data.download

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class PackageDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val payload = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    private var allowed = true
    private val policy = NetworkPolicy { allowed }

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/pkgs/manifest.json" -> MockResponse().setBody(
                    """{"version":1,"countries":{"NO":[{"type":"map","file":"no.pmtiles","bytes":${payload.size},"sha256":"$sha"}]}}""",
                )
                "/pkgs/no.pmtiles" -> {
                    val range = request.getHeader("Range")
                    if (range != null) {
                        val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                        MockResponse().setResponseCode(206)
                            .setHeader("Content-Range", "bytes $from-${payload.size - 1}/${payload.size}")
                            .setBody(Buffer().write(payload, from, payload.size - from))
                    } else {
                        MockResponse().setBody(Buffer().write(payload))
                    }
                }
                "/pkgs/bad.pmtiles" -> MockResponse().setBody(Buffer().write(ByteArray(payload.size)))
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun base() = server.url("/pkgs/").toString()

    @Test fun fetchesManifest() = runTest {
        val m = PackageDownloader(policy).fetchManifest(base().removeSuffix("/"))
        assertThat(m.countries["NO"]!!.single().file).isEqualTo("no.pmtiles")
        assertThat(m.bytesFor(listOf("NO"))).isEqualTo(payload.size.toLong())
    }

    @Test fun downloadsAndVerifiesChecksum() = runTest {
        val target = tmp.root.resolve("NO/no.pmtiles")
        val part = tmp.root.resolve("NO/no.pmtiles.part")
        PackageDownloader(policy).download(PackageDownloader.resolve(base(), "no.pmtiles"), part, target, payload.size.toLong(), sha)
        assertThat(target.readBytes()).isEqualTo(payload)
        assertThat(part.exists()).isFalse()
    }

    @Test fun resumesWithRange() = runTest {
        val target = tmp.root.resolve("no.pmtiles")
        val part = tmp.root.resolve("no.pmtiles.part")
        part.writeBytes(payload.copyOfRange(0, 120_000))
        PackageDownloader(policy).download(PackageDownloader.resolve(base(), "no.pmtiles"), part, target, payload.size.toLong(), sha)
        val req = server.takeRequest()
        assertThat(req.getHeader("Range")).isEqualTo("bytes=120000-")
        assertThat(target.readBytes()).isEqualTo(payload)
    }

    @Test fun wrongChecksumIsRejected() = runTest {
        val target = tmp.root.resolve("bad.pmtiles")
        val part = tmp.root.resolve("bad.pmtiles.part")
        try {
            PackageDownloader(policy).download(PackageDownloader.resolve(base(), "bad.pmtiles"), part, target, payload.size.toLong(), sha)
            fail("Prüfsumme hätte fehlschlagen müssen")
        } catch (e: DownloadException) {
            assertThat(e.message).contains("Prüfsumme")
        }
        assertThat(target.exists()).isFalse()
        assertThat(part.exists()).isFalse()
    }

    /** Spezifikation 8: Test, dass nach offlineLocked kein Request rausgeht. */
    @Test fun noRequestWhenOfflineLocked() = runTest {
        allowed = false
        val d = PackageDownloader(policy)
        try {
            d.fetchManifest(base())
            fail("Request hätte verweigert werden müssen")
        } catch (e: OfflineLockedException) { /* erwartet */ }
        try {
            d.download(PackageDownloader.resolve(base(), "no.pmtiles"), tmp.root.resolve("x.part"), tmp.root.resolve("x"), 0, "")
            fail("Request hätte verweigert werden müssen")
        } catch (e: OfflineLockedException) { /* erwartet */ }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun planSkipsSharedRoutingTilesAndChecksSpace() {
        val m = de.tetnavigator.core.offlinedata.Manifest.parse(
            """{"countries":{
              "NO":[{"type":"map","file":"no.pmtiles","bytes":100},{"type":"routing","file":"E5_N60.rd5","bytes":50}],
              "SE":[{"type":"map","file":"se.pmtiles","bytes":80},{"type":"routing","file":"E5_N60.rd5","bytes":50}]}}""",
        )
        val plan = DownloadPlan.create(m, listOf("NO", "SE")) { cc, f ->
            val t = tmp.root.resolve(de.tetnavigator.core.offlinedata.OfflineStorage.relativePath(cc, f))
            t to java.io.File(t.path + ".part")
        }
        assertThat(plan.files).hasSize(3)
        assertThat(plan.totalBytes).isEqualTo(230)
        assertThat(plan.fitsInto(10)).isFalse()
        assertThat(plan.fitsInto(100L * 1024 * 1024)).isTrue()
    }
}
