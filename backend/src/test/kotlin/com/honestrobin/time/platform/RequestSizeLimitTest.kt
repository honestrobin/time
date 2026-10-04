// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform

import com.honestrobin.time.platform.web.ApiExceptionHandler
import com.honestrobin.time.platform.web.LimitedInputStream
import com.honestrobin.time.platform.web.RequestSizeLimitFilter
import com.honestrobin.time.platform.web.RequestTooLargeException
import com.honestrobin.time.support.IntegrationTest
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.mock.http.MockHttpInputMessage

class RequestSizeLimitTest : IntegrationTest() {

    @Test
    fun `a webhook body over 1 MB is refused before anything reads it, with no account`() {
        val body = "[" + "{},".repeat((RequestSizeLimitFilter.WEBHOOK_LIMIT / 3).toInt()) + "{}]"
        client().request(HttpMethod.POST, "/webhooks/stripe", body, headers = mapOf("Stripe-Signature" to "t=1,v1=x")).expectError(413, "too_large")
        client().request(HttpMethod.POST, "/webhooks/storecove", body).expectError(413, "too_large")
    }

    @Test
    fun `other request bodies over 2 MB are refused, and smaller ones still work`() {
        val padding = "x".repeat((RequestSizeLimitFilter.LIMIT + 1).toInt())
        client().post("/api/v1/auth/magic_link", mapOf("email" to "a@example.test", "padding" to padding)).expectError(413, "too_large")
        client().post("/api/v1/auth/magic_link", mapOf("email" to "a@example.test", "padding" to "x".repeat(100_000))).expect(202)
    }

    @Test
    fun `a body sent without a length is counted as it is read`() {
        val source = "x".repeat(11).byteInputStream()
        val stream = LimitedInputStream(
            object : ServletInputStream() {
                override fun read() = source.read()
                override fun isFinished() = source.available() == 0
                override fun isReady() = true
                override fun setReadListener(listener: ReadListener) {}
            },
            10,
        )
        assertThatThrownBy { stream.readAllBytes() }.isInstanceOf(RequestTooLargeException::class.java)
    }

    @Test
    fun `reading past the limit while parsing answers 413, not 400`() {
        val e = HttpMessageNotReadableException("I/O error while reading input message", RequestTooLargeException(10), MockHttpInputMessage(ByteArray(0)))
        val response = ApiExceptionHandler().unreadable(e)
        assertThat(response.statusCode.value()).isEqualTo(413)
        assertThat(response.body!!.code).isEqualTo("too_large")
    }
}
