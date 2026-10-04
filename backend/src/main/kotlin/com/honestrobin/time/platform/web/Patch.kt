// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.web

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

/**
 * A PATCH body plus the set of (snake_case) fields the client actually sent, so that
 * `"hourly_rate": null` (clear it) can be told apart from omitting the field (leave it).
 */
class Patch<T>(val value: T, val present: Set<String>) {
    fun has(field: String) = field in present

    /** Calls [apply] with the field's value if the client sent the field, even when it is null. */
    inline fun <R> field(name: String, get: T.() -> R, apply: (R) -> Unit) {
        if (has(name)) apply(value.get())
    }

    companion object {
        fun <T> all(value: T, fields: Set<String>) = Patch(value, fields)
    }
}

@Component
class Patches(private val mapper: ObjectMapper) {
    fun <T> parse(body: JsonNode, type: Class<T>): Patch<T> {
        if (!body.isObject) throw BadRequestException("bad_request", "Expected a JSON object")
        val value = try {
            mapper.treeToValue(body, type)
        } catch (e: Exception) {
            throw BadRequestException("bad_request", e.message?.substringBefore("\n") ?: "Malformed request")
        }
        return Patch(value, body.propertyNames().toSet())
    }

    final inline fun <reified T> parse(body: JsonNode): Patch<T> = parse(body, T::class.java)
}
