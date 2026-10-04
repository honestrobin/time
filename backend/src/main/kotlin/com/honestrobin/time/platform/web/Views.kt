// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.web

import com.fasterxml.jackson.annotation.JsonView
import com.honestrobin.time.platform.security.Current
import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice

/**
 * JSON views for rate/amount visibility (spec §11, AT-1.5). Properties annotated with
 * `@JsonView(Views.Rates::class)` are only serialised for callers who may see rates and amounts.
 * Everything else is public (`default-view-inclusion` is on).
 */
object Views {
    interface Public

    interface Rates : Public
}

/** Picks the view per request: the JSON converter serialises with it (Spring 7 "write hints"). */
@RestControllerAdvice(basePackages = ["com.honestrobin.time"])
class RateVisibilityAdvice : ResponseBodyAdvice<Any> {
    override fun supports(returnType: MethodParameter, converterType: Class<out HttpMessageConverter<*>>): Boolean =
        JacksonJsonHttpMessageConverter::class.java.isAssignableFrom(converterType)

    override fun beforeBodyWrite(
        body: Any?,
        returnType: MethodParameter,
        selectedContentType: MediaType,
        selectedConverterType: Class<out HttpMessageConverter<*>>,
        request: ServerHttpRequest,
        response: ServerHttpResponse,
    ): Any? = body

    override fun determineWriteHints(
        body: Any?,
        returnType: MethodParameter,
        selectedContentType: MediaType,
        selectedConverterType: Class<out HttpMessageConverter<*>>,
    ): Map<String, Any> {
        val member = Current.memberOrNull()
        val view = if (member?.canSeeRates == true) Views.Rates::class.java else Views.Public::class.java
        return mapOf(JsonView::class.java.name to view)
    }
}
