// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.support

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** A stand-in for the parts of Storecove's API the app calls. */
object MockStorecove {
    const val PLATFORM_KEY = "sc_platform"
    const val PLATFORM_WEBHOOK_SECRET = "sc_platform_webhook"
    const val OWN_KEY = "sc_own_account"

    data class Call(val method: String, val path: String, val authorization: String?, val body: JsonNode?)

    val calls = CopyOnWriteArrayList<Call>()

    /** Identifiers (Storecove scheme:identifier) that can receive over Peppol. */
    val reachable: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Identifiers Storecove refuses to send to. */
    val refused: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private val mapper = ObjectMapper()
    private val server: HttpServer by lazy {
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val raw = ex.requestBody.readAllBytes()
                val body = if (raw.isEmpty()) null else mapper.readTree(raw)
                val auth = ex.requestHeaders.getFirst("Authorization")
                calls.add(Call(ex.requestMethod, ex.requestURI.path, auth, body))
                val path = ex.requestURI.path.removePrefix("/api/v2")
                val (status, response) = when {
                    auth != "Bearer $PLATFORM_KEY" && auth != "Bearer $OWN_KEY" -> 401 to mapOf("message" to "Unauthorized")
                    path == "/legal_entities" -> 200 to mapOf("id" to (1000..9999).random())
                    path.matches(Regex("/legal_entities/\\d+/peppol_identifiers")) -> 200 to mapOf("superscheme" to "iso6523-actorid-upis")
                    path == "/discovery/receives" -> 200 to mapOf("code" to if ("${body!!["scheme"].asText()}:${body["identifier"].asText()}" in reachable) "OK" else "NOK", "email" to false)
                    path == "/document_submissions" -> {
                        val e = body!!["routing"]["eIdentifiers"][0]
                        if ("${e["scheme"].asText()}:${e["id"].asText()}" in refused) 422 to mapOf("message" to "Receiver not found") else 200 to mapOf("guid" to UUID.randomUUID().toString())
                    }
                    else -> 404 to mapOf("message" to "Not found")
                }
                val bytes = mapper.writeValueAsBytes(response)
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/api/v2"
}
