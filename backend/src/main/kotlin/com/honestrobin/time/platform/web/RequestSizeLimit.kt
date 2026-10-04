// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.web

import tools.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.Charset

/**
 * Caps request bodies that are read into memory: JSON, webhooks, everything that isn't a multipart
 * upload (Spring's multipart limits cover those, and parts go to disk) or the account import (it
 * streams to disk under its own limit). Without a cap, one request of a few dozen megabytes to a
 * public endpoint, with no account, could use up the heap and stop the app.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class RequestSizeLimitFilter(private val objectMapper: ObjectMapper) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.requestURI == ACCOUNT_IMPORT_PATH || request.contentType?.startsWith("multipart/", ignoreCase = true) == true

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val limit = limitFor(request.requestURI)
        if (request.contentLengthLong > limit) {
            response.status = HttpStatus.PAYLOAD_TOO_LARGE.value()
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            objectMapper.writeValue(response.outputStream, tooLarge(limit))
            return
        }
        // A body sent without a length (chunked) is counted as it's read.
        chain.doFilter(LimitedRequest(request, limit), response)
    }

    companion object {
        const val ACCOUNT_IMPORT_PATH = "/api/v1/accounts/import"

        /** Webhook events are small; a payment provider's largest is a few dozen kilobytes. */
        const val WEBHOOK_LIMIT = 1L shl 20

        /** Generous for any JSON the app takes (a bulk edit of a thousand entries is far below it). */
        const val LIMIT = 2L shl 20

        fun limitFor(path: String) = if (path.startsWith("/webhooks/")) WEBHOOK_LIMIT else LIMIT

        fun tooLarge(limit: Long) = ApiError("too_large", "The request is larger than ${limit shr 20} MB, the most this endpoint accepts")
    }
}

/** Thrown while reading a body past its limit; answered with 413 (see [ApiExceptionHandler]). */
class RequestTooLargeException(val limit: Long) : IOException("Request body larger than $limit bytes")

internal class LimitedRequest(request: HttpServletRequest, private val limit: Long) : HttpServletRequestWrapper(request) {
    private val stream by lazy { LimitedInputStream(request.inputStream, limit) }

    override fun getInputStream(): ServletInputStream = stream

    override fun getReader(): BufferedReader =
        BufferedReader(InputStreamReader(stream, characterEncoding?.let(Charset::forName) ?: Charsets.UTF_8))
}

internal class LimitedInputStream(private val delegate: ServletInputStream, private val limit: Long) : ServletInputStream() {
    private var count = 0L

    override fun read(): Int {
        val b = delegate.read()
        if (b >= 0) counted(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = delegate.read(b, off, len)
        if (n > 0) counted(n)
        return n
    }

    private fun counted(n: Int) {
        count += n
        if (count > limit) throw RequestTooLargeException(limit)
    }

    override fun isFinished() = delegate.isFinished

    override fun isReady() = delegate.isReady

    override fun setReadListener(listener: ReadListener) = delegate.setReadListener(listener)

    override fun close() = delegate.close()
}
