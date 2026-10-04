// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform

import com.honestrobin.time.support.IntegrationTest
import tools.jackson.databind.SerializationFeature
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Writes the OpenAPI 3.1 document to packages/api-client/openapi.json, from which the
 * TypeScript client is generated. CI fails if the committed file is out of date.
 */
class OpenApiExportTest : IntegrationTest() {
    @Test
    fun `export OpenAPI document`() {
        val doc = client().get("/v3/api-docs").expect(200).body
        assertThat(doc["openapi"].asText()).startsWith("3.1")
        assertThat(doc["paths"].has("/api/v1/me")).isTrue()
        (doc as tools.jackson.databind.node.ObjectNode).remove("servers")
        // The committed document describes the self-hosted edition; the cloud edition adds its billing endpoints.
        if (System.getProperty("honestrobin.edition") == "cloud") return
        val out = File("../packages/api-client/openapi.json")
        out.writeText(mapper.writer(SerializationFeature.INDENT_OUTPUT, SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(doc) + "\n")
    }
}
