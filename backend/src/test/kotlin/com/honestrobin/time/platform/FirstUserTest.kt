// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform

import com.honestrobin.time.support.RecordingMailTransport
import com.honestrobin.time.support.TestClient
import com.honestrobin.time.support.TestDatabase
import tools.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc

/** AT-0.1 (API level): on a fresh self-hosted instance, the first user becomes admin and sign-up then closes. */
@SpringBootTest(properties = ["honestrobin.signup-mode=first_user_only"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FirstUserTest {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var mapper: ObjectMapper

    @Autowired
    lateinit var mail: RecordingMailTransport

    @Test
    fun `first user becomes admin and later sign-ups are refused`() {
        val c = TestClient(mvc, mapper)
        val before = c.get("/api/v1/auth/config").expect(200)
        assertThat(before["needs_setup"].asBoolean()).isTrue()
        assertThat(before["signup_allowed"].asBoolean()).isTrue()

        c.post(
            "/api/v1/auth/signup",
            mapOf("name" to "Owner", "email" to "owner@example.test", "password" to "correct horse battery", "account_name" to "Owner Co"),
        ).expect(201)
        val me = c.get("/api/v1/me").expect(200)
        assertThat(me["is_instance_admin"].asBoolean()).isTrue()
        assertThat(me["role"].asText()).isEqualTo("admin")

        val after = TestClient(mvc, mapper).get("/api/v1/auth/config").expect(200)
        assertThat(after["needs_setup"].asBoolean()).isFalse()
        assertThat(after["signup_allowed"].asBoolean()).isFalse()

        TestClient(mvc, mapper).post(
            "/api/v1/auth/signup",
            mapOf("name" to "Second", "email" to "second@example.test", "password" to "correct horse battery", "account_name" to "Second Co"),
        ).expectError(403, "signup_closed")

        // The instance admin can still create further accounts.
        c.post("/api/v1/accounts", mapOf("name" to "Side Project")).expect(201)
        assertThat(c.get("/api/v1/me").expect(200)["accounts"]).hasSize(2)
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            val url = TestDatabase.freshDatabase("firstuser")
            registry.add("spring.datasource.url") { url }
            registry.add("spring.datasource.username") { TestDatabase.username }
            registry.add("spring.datasource.password") { TestDatabase.password }
        }
    }
}
