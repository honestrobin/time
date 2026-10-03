// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.support

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** A stand-in for PostHog's capture endpoint, recording every event it receives. */
object MockPostHog {
    const val API_KEY = "phc_test"

    val events = CopyOnWriteArrayList<JsonNode>()

    private val mapper = ObjectMapper()
    private val server: HttpServer by lazy {
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/capture/") { ex ->
                events.add(mapper.readTree(ex.requestBody.readAllBytes()))
                ex.sendResponseHeaders(200, 2)
                ex.responseBody.use { it.write("{}".toByteArray()) }
            }
            start()
        }
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    fun forAccount(accountId: Any): List<String> = events.filter { it["distinct_id"].asText() == accountId.toString() }.map { it["event"].asText() }
}
