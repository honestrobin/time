// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform

import com.honestrobin.time.platform.crypto.Totp
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
import java.time.Instant

/**
 * With sign-up closed, the one unconfirmed address is the first user's, typed by the instance's
 * owner. A sign-in link used to treat it like a squatted address and remove the owner's password
 * and two-factor sign-in (security review, 4 October 2026).
 */
@SpringBootTest(properties = ["honestrobin.signup-mode=first_user_only"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClosedSignupTest {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var mapper: ObjectMapper

    @Autowired
    lateinit var mail: RecordingMailTransport

    @Test
    fun `a sign-in link confirms the owner's address and keeps their two-factor sign-in`() {
        val email = "owner@example.test"
        val owner = TestClient(mvc, mapper)
        owner.post(
            "/api/v1/auth/signup",
            mapOf("name" to "Owner", "email" to email, "password" to "correct horse battery", "account_name" to "Owner Co"),
        ).expect(201)
        assertThat(owner.get("/api/v1/me").expect(200)["email_verified"].asBoolean()).isFalse()
        val secret = owner.post("/api/v1/me/two_factor/setup").expect(200)["secret"].asText().replace(" ", "")
        val step = Totp.step(Instant.now())
        owner.post("/api/v1/me/two_factor/enable", mapOf("code" to Totp.code(secret, step))).expect(200)

        // Whoever opens the link still needs the code.
        TestClient(mvc, mapper).post("/api/v1/auth/magic_link", mapOf("email" to email)).expect(202)
        val c = TestClient(mvc, mapper)
        val res = c.post("/api/v1/auth/magic_link/consume", mapOf("token" to mail.linkToken(email))).expect(200)
        assertThat(res["two_factor_required"].asBoolean()).isTrue()
        c.get("/api/v1/me").expect(401)
        c.post("/api/v1/auth/two_factor", mapOf("challenge" to res["challenge"].asText(), "code" to Totp.code(secret, step + 1))).expect(200)
        assertThat(c.get("/api/v1/me").expect(200)["email_verified"].asBoolean()).isTrue()

        // The password still works, and still asks for the code.
        val login = TestClient(mvc, mapper).post("/api/v1/auth/login", mapOf("email" to email, "password" to "correct horse battery")).expect(200)
        assertThat(login["two_factor_required"].asBoolean()).isTrue()
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            val url = TestDatabase.freshDatabase("closedsignup")
            registry.add("spring.datasource.url") { url }
            registry.add("spring.datasource.username") { TestDatabase.username }
            registry.add("spring.datasource.password") { TestDatabase.password }
        }
    }
}
