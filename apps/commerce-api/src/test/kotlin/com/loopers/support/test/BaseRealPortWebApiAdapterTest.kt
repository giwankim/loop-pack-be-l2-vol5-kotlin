package com.loopers.support.test

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalManagementPort
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The base for tests that read the Actuator as a scraper does: the application runs on a real port, and the management server
 * on its own (spec 266, story 25). It widens [BaseWebApiAdapterTest], so requests to the application still go through
 * [mvc] on the test thread under the inherited test transaction. Only [managementGet] goes over real HTTP, to the management port.
 *
 * With `RANDOM_PORT` Boot sets the management port to 0 as well, so no test needs 8081 to be free. [managementPort] is the port
 * the management server was given. The management server answers on its own threads, so what it reads from the database sees
 * only committed rows, and the `commerce.payment.unresolved` gauge moves only after a commit (spec 281): a scene that reads either
 * leaves the test transaction like [BaseCommittingApplicationServiceTest].
 * The real port splits the context from [BaseWebApiAdapterTest]'s, one more context (ADR 0012).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class BaseRealPortWebApiAdapterTest : BaseWebApiAdapterTest() {
    companion object {
        /** Long enough for a busy JVM's first scrape. */
        private val MANAGEMENT_TIMEOUT: Duration = Duration.ofSeconds(10)
    }

    @LocalManagementPort
    protected var managementPort: Int = 0

    /** A GET to [path] on the management port over real HTTP, answered whatever its status. */
    protected fun managementGet(path: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$managementPort$path"))
            .timeout(MANAGEMENT_TIMEOUT)
            .GET()
            .build()

        return HttpClient.newHttpClient().use { client ->
            client.send(request, HttpResponse.BodyHandlers.ofString())
        }
    }
}
