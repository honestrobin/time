// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.support

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.MessageDigest

/** Stand-in for the Have I Been Pwned range API (k-anonymity: the first five hash characters in, suffixes out). */
object MockPwned {
    /** Passwords this stand-in reports as breached. */
    val breached = setOf("breached but long enough", "Tr0ub4dor&3-leaked")

    /** When set, every request fails, as if the service were down. */
    @Volatile var failing = false

    @Volatile var requests = 0

    private val server: HttpServer by lazy {
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/range/") { ex ->
                requests++
                val prefix = ex.requestURI.path.substringAfterLast('/').uppercase()
                val body = if (failing) {
                    ""
                } else {
                    val hits = breached.map(::sha1).filter { it.startsWith(prefix) }.map { "${it.drop(5)}:${1000}" }
                    // Padding, as the real service adds with Add-Padding: true.
                    (hits + "0123456789ABCDEF0123456789ABCDEF012:0").joinToString("\r\n")
                }
                val bytes = body.toByteArray()
                ex.sendResponseHeaders(if (failing) 503 else 200, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                ex.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) }
            }
            start()
        }
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02X".format(it) }
}
