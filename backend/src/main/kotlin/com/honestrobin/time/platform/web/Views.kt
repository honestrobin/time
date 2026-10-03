// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.web

import com.honestrobin.time.platform.security.Current
import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJacksonValue
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.AbstractMappingJacksonResponseBodyAdvice

/**
 * JSON views for rate/amount visibility (spec §11, AT-1.5). Properties annotated with
 * `@JsonView(Views.Rates::class)` are only serialised for callers who may see rates and amounts.
 * Everything else is public (`default-view-inclusion` is on).
 */
object Views {
    interface Public

    interface Rates : Public
}

@RestControllerAdvice(basePackages = ["com.honestrobin.time"])
class RateVisibilityAdvice : AbstractMappingJacksonResponseBodyAdvice() {
    override fun beforeBodyWriteInternal(
        bodyContainer: MappingJacksonValue,
        contentType: MediaType,
        returnType: MethodParameter,
        request: ServerHttpRequest,
        response: ServerHttpResponse,
    ) {
        val member = Current.memberOrNull()
        bodyContainer.serializationView = if (member?.canSeeRates == true) Views.Rates::class.java else Views.Public::class.java
    }
}
