// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.web

import com.fasterxml.jackson.annotation.JsonIgnore
import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.json.MappingJacksonValue
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice
import java.time.Instant

/** A resource with optimistic concurrency: its ETag derives from `updated_at`. */
interface Versioned {
    @get:JsonIgnore
    val version: Instant
}

object ETags {
    fun of(updatedAt: Instant) = "W/\"${updatedAt.epochSecond}${"%06d".format(updatedAt.nano / 1000)}\""

    /**
     * Enforces `If-Match` for updates and deletes (spec §10). Absent header means "last write wins",
     * which keeps simple scripts simple.
     */
    fun checkIfMatch(current: Instant) {
        val request = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request ?: return
        val header = request.getHeader("If-Match") ?: return
        if (header.trim() == "*") return
        val candidates = header.split(",").map { it.trim() }
        if (of(current) !in candidates) throw PreconditionFailedException()
    }
}

@RestControllerAdvice(basePackages = ["com.honestrobin.time"])
class ETagAdvice : ResponseBodyAdvice<Any> {
    override fun supports(returnType: MethodParameter, converterType: Class<out HttpMessageConverter<*>>) = true

    override fun beforeBodyWrite(
        body: Any?,
        returnType: MethodParameter,
        selectedContentType: MediaType,
        selectedConverterType: Class<out HttpMessageConverter<*>>,
        request: ServerHttpRequest,
        response: ServerHttpResponse,
    ): Any? {
        val value = (body as? MappingJacksonValue)?.value ?: body
        if (value is Versioned) response.headers.eTag = ETags.of(value.version)
        return body
    }
}
