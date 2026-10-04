// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.support

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.MissingNode
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import java.util.UUID

class TestResponse(val status: Int, val body: JsonNode, val raw: String, val headers: Map<String, List<String>>, val bytes: ByteArray) {
    fun expect(expected: Int): TestResponse {
        assertThat(status).withFailMessage { "Expected HTTP $expected but got $status: $raw" }.isEqualTo(expected)
        return this
    }

    fun expectError(expected: Int, code: String): TestResponse {
        expect(expected)
        assertThat(body["code"]?.asText()).withFailMessage { "Expected error code $code in $raw" }.isEqualTo(code)
        return this
    }

    operator fun get(field: String): JsonNode = body[field] ?: MissingNode.getInstance()

    fun id(): UUID = UUID.fromString(body["id"].asText())
}

/** A browser-like client: keeps cookies, sends the CSRF header and the account header. */
class TestClient(private val mvc: MockMvc, private val mapper: ObjectMapper) {
    val cookies = mutableMapOf<String, Cookie>()
    var accountId: UUID? = null
    var bearer: String? = null
    var email: String? = null
    var userId: UUID? = null
    var membershipId: UUID? = null
    var sendCsrf = true

    fun get(path: String, params: Map<String, Any?> = emptyMap()) = request(HttpMethod.GET, path, null, params)
    fun post(path: String, body: Any? = null) = request(HttpMethod.POST, path, body)
    fun put(path: String, body: Any? = null) = request(HttpMethod.PUT, path, body)
    fun patch(path: String, body: Any? = null) = request(HttpMethod.PATCH, path, body)
    fun delete(path: String, body: Any? = null) = request(HttpMethod.DELETE, path, body)

    fun upload(path: String, field: String, filename: String, contentType: String, bytes: ByteArray, params: Map<String, String> = emptyMap()): TestResponse {
        ensureCsrf()
        val builder = MockMvcRequestBuilders.multipart(path).file(MockMultipartFile(field, filename, contentType, bytes))
        params.forEach { (k, v) -> builder.param(k, v) }
        return perform(builder)
    }

    fun request(method: HttpMethod, path: String, body: Any?, params: Map<String, Any?> = emptyMap(), headers: Map<String, String> = emptyMap()): TestResponse {
        if (method != HttpMethod.GET) ensureCsrf()
        val builder = MockMvcRequestBuilders.request(method, path)
        params.forEach { (k, v) -> if (v != null) builder.param(k, v.toString()) }
        headers.forEach { (k, v) -> builder.header(k, v) }
        when (body) {
            null -> {}
            // Raw bytes (e.g. an export zip) go as they are, with the Content-Type from [headers].
            is ByteArray -> builder.content(body)
            else -> builder.contentType(MediaType.APPLICATION_JSON).content(if (body is String) body else mapper.writeValueAsString(body))
        }
        return perform(builder)
    }

    private fun perform(builder: AbstractMockHttpServletRequestBuilder<*>): TestResponse {
        if (cookies.isNotEmpty()) builder.cookie(*cookies.values.toTypedArray())
        cookies["XSRF-TOKEN"]?.let { if (sendCsrf) builder.header("X-XSRF-TOKEN", it.value) }
        accountId?.let { builder.header("HonestRobin-Account-Id", it.toString()) }
        bearer?.let { builder.header("Authorization", "Bearer $it") }
        val result = mvc.perform(builder).andReturn()
        val res = result.response
        res.cookies.forEach { c -> if (c.maxAge == 0) cookies.remove(c.name) else cookies[c.name] = c }
        val raw = res.contentAsString
        val json = if (raw.isNotBlank() && res.contentType?.contains("json") == true) mapper.readTree(raw) else MissingNode.getInstance()
        return TestResponse(res.status, json, raw, res.headerNames.associateWith { res.getHeaders(it) }, res.contentAsByteArray)
    }

    private fun ensureCsrf() {
        if (!cookies.containsKey("XSRF-TOKEN") && bearer == null) get("/api/v1/auth/config")
    }
}
