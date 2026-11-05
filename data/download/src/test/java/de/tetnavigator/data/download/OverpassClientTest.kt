package de.tetnavigator.data.download

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OverpassClientTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun fallsBackToNextServerAndRejectsTimeouts() = runTest {
        val busy = MockWebServer().apply { enqueue(MockResponse().setResponseCode(429)); start() }
        val timeout = MockWebServer().apply {
            enqueue(MockResponse().setBody("""{"elements":[],"remark":"runtime error: Query timed out in \"query\" at line 3 after 601 seconds."}""")); start()
        }
        val ok = MockWebServer().apply { enqueue(MockResponse().setBody("""{"elements":[{"type":"node","id":1,"lat":1.0,"lon":2.0,"tags":{"amenity":"fuel"}}]}""")); start() }
        val target = File(tmp.root, "op.json")
        OverpassClient(HttpClients.create { true }, listOf(busy, timeout, ok).map { it.url("/api/interpreter").toString() })
            .download("[out:json];node(1);out;", target)
        assertThat(target.readText()).contains("\"fuel\"")
        val req = ok.takeRequest()
        assertThat(req.method).isEqualTo("POST")
        assertThat(req.body.readUtf8()).startsWith("data=")
        assertThat(req.getHeader("User-Agent")).contains("TET-Navigator")
        listOf(busy, timeout, ok).forEach { it.shutdown() }
    }

    @Test fun offlineLockBlocksOverpass() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            OverpassClient(HttpClients.create { false }, listOf(server.url("/").toString())).download("x", File(tmp.root, "o.json"))
            error("hätte gesperrt sein müssen")
        } catch (e: OfflineLockedException) { /* erwartet */ }
        assertThat(server.requestCount).isEqualTo(0)
        server.shutdown()
    }
}
