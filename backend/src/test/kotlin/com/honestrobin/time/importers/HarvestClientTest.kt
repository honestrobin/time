// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.importers

import com.honestrobin.time.importers.harvest.HarvestApiException
import com.honestrobin.time.importers.harvest.HarvestClient
import com.honestrobin.time.importers.harvest.RateLimiter
import com.honestrobin.time.importers.harvest.Sleeper
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** Security review, 4 October 2026: the Harvest token stays with Harvest, and receipts have a size limit. */
class HarvestClientTest {
    private val seen = CopyOnWriteArrayList<Pair<String, String?>>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            seen += ex.requestURI.path to ex.requestHeaders.getFirst("Authorization")
            when (ex.requestURI.path) {
                "/v2/users/me" -> respond(ex, 200, """{"id":1}""".toByteArray())
                "/v2/moved" -> {
                    ex.responseHeaders.add("Location", "http://127.0.0.1:${address.port}/elsewhere")
                    ex.sendResponseHeaders(302, -1)
                    ex.close()
                }
                "/receipts/small" -> respond(ex, 200, ByteArray(10))
                "/receipts/large" -> respond(ex, 200, ByteArray(11))
                else -> respond(ex, 404, "{}".toByteArray())
            }
        }
        start()
    }

    private fun respond(ex: com.sun.net.httpserver.HttpExchange, status: Int, bytes: ByteArray) {
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private val base = "http://127.0.0.1:${server.address.port}"
    private val client = HarvestClient(
        baseUrl = "$base/v2", token = "harvest-token", accountId = "42", userAgent = "test",
        general = RateLimiter(100, Duration.ofSeconds(15), Sleeper {}), reports = RateLimiter(100, Duration.ofMinutes(15), Sleeper {}),
        sleeper = Sleeper {}, maxReceiptBytes = 10,
    )

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `the token only goes to Harvest's own address`() {
        assertThat(client.get("$base/v2/users/me")["id"].asInt()).isEqualTo(1)
        // A page link to anywhere else isn't followed, and nothing is sent there.
        assertThatThrownBy { client.get("http://attacker.example/v2/time_entries?page=2") }.isInstanceOf(HarvestApiException::class.java).hasMessageContaining("attacker.example")
        assertThatThrownBy { client.get("https://127.0.0.1:${server.address.port}/v2/users/me") }.isInstanceOf(HarvestApiException::class.java)
        // Nor is a redirect, which would take the token with it.
        assertThatThrownBy { client.get("$base/v2/moved") }.isInstanceOf(HarvestApiException::class.java)
        assertThat(seen.map { it.first }).doesNotContain("/elsewhere")
        assertThat(seen.filter { it.second != null }.map { it.first }).containsExactly("/v2/users/me", "/v2/moved")
    }

    @Test
    fun `receipts are downloaded up to the size limit, without the token`() {
        assertThat(client.download("$base/receipts/small").first).hasSize(10)
        assertThatThrownBy { client.download("$base/receipts/large") }.isInstanceOf(HarvestApiException::class.java).hasMessageContaining("larger than")
        assertThat(seen.filter { it.first.startsWith("/receipts/") }.map { it.second }).containsOnlyNulls()
    }
}
