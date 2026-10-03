// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.auth

import com.honestrobin.time.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AuthFlowTest : IntegrationTest() {

    @Test
    fun `signup signs the user in as admin of a new account`() {
        val c = signup(name = "Ada", accountName = "Ada Studio")
        val me = c.get("/api/v1/me").expect(200)
        assertThat(me["name"].asText()).isEqualTo("Ada")
        assertThat(me["accounts"]).hasSize(1)
        assertThat(me["accounts"][0]["role"].asText()).isEqualTo("admin")
        assertThat(me["current_account_id"].asText()).isEqualTo(c.accountId.toString())
        assertThat(me["permissions"]["can_see_rates"].asBoolean()).isTrue()

        val account = c.get("/api/v1/account").expect(200)
        assertThat(account["name"].asText()).isEqualTo("Ada Studio")
        assertThat(account["timezone"].asText()).isEqualTo("Europe/Zagreb")
    }

    @Test
    fun `signup takes the time zone, currency and week start of wherever the user is`() {
        val c = client()
        fun body(weekStart: Int) = mapOf(
            "name" to "Kenji", "email" to uniqueEmail(), "password" to "correct horse battery", "account_name" to "Kenji Design",
            "timezone" to "Asia/Tokyo", "default_currency" to "JPY", "week_start" to weekStart,
        )
        val bad = c.post("/api/v1/auth/signup", body(9))
        bad.expectError(422, "validation_failed")
        assertThat(bad["fields"]["week_start"].asText()).isNotBlank()

        c.post("/api/v1/auth/signup", body(7)).expect(201)
        val account = c.get("/api/v1/account").expect(200)
        assertThat(account["timezone"].asText()).isEqualTo("Asia/Tokyo")
        assertThat(account["default_currency"].asText()).isEqualTo("JPY")
        assertThat(account["week_start"].asInt()).isEqualTo(7)
    }

    @Test
    fun `sign-in emails to one address are limited, without revealing that it has an account`() {
        val email = signup().email!!
        val before = mail.to(email).size
        repeat(5) { client().post("/api/v1/auth/magic_link", mapOf("email" to email)).expect(202) }
        client().post("/api/v1/auth/password_reset", mapOf("email" to email.uppercase())).expect(202)
        // Three emails per address in 15 minutes, and the sign-up confirmation was the first.
        assertThat(mail.to(email).size - before).isEqualTo(2)

        clock.advance(java.time.Duration.ofMinutes(16))
        client().post("/api/v1/auth/magic_link", mapOf("email" to email)).expect(202)
        assertThat(mail.to(email).size - before).isEqualTo(3)
    }

    @Test
    fun `session cookie is HttpOnly and SameSite=Lax`() {
        val c = client()
        val email = uniqueEmail()
        val res = c.post(
            "/api/v1/auth/signup",
            mapOf("name" to "A", "email" to email, "password" to "correct horse battery", "account_name" to "A"),
        ).expect(201)
        val setCookie = res.headers["Set-Cookie"]!!.first { it.startsWith("honestrobin_session=") }
        assertThat(setCookie).contains("HttpOnly").contains("SameSite=Lax")
    }

    @Test
    fun `duplicate email and weak password are rejected`() {
        val c = signup()
        client().post(
            "/api/v1/auth/signup",
            mapOf("name" to "B", "email" to c.email!!.uppercase(), "password" to "correct horse battery", "account_name" to "B"),
        ).expectError(409, "email_taken")
        val weak = client().post("/api/v1/auth/signup", mapOf("name" to "B", "email" to uniqueEmail(), "password" to "short", "account_name" to "B"))
        weak.expectError(422, "validation_failed")
        assertThat(weak["fields"]["password"].asText()).isNotBlank()
    }

    @Test
    fun `logout then login with password`() {
        val c = signup()
        c.post("/api/v1/auth/logout").expect(204)
        c.get("/api/v1/me").expectError(401, "unauthenticated")

        c.post("/api/v1/auth/login", mapOf("email" to c.email, "password" to "wrong password!")).expectError(401, "invalid_credentials")
        c.post("/api/v1/auth/login", mapOf("email" to c.email, "password" to "correct horse battery")).expect(200)
        c.get("/api/v1/me").expect(200)
    }

    @Test
    fun `magic link signs in once`() {
        val c = signup()
        val email = c.email!!
        val fresh = client()
        fresh.post("/api/v1/auth/magic_link", mapOf("email" to email)).expect(202)
        val token = mail.linkToken(email)
        assertThat(mail.lastTo(email).subject).contains("sign-in link")

        fresh.post("/api/v1/auth/magic_link/consume", mapOf("token" to token)).expect(200)
        assertThat(fresh.get("/api/v1/me").expect(200)["email"].asText()).isEqualTo(email)

        client().post("/api/v1/auth/magic_link/consume", mapOf("token" to token)).expectError(400, "invalid_link")
    }

    @Test
    fun `magic link request for unknown email does not reveal anything`() {
        val email = uniqueEmail("nobody")
        client().post("/api/v1/auth/magic_link", mapOf("email" to email)).expect(202)
        assertThat(mail.to(email)).isEmpty()
    }

    @Test
    fun `password reset revokes other sessions`() {
        val c = signup()
        val email = c.email!!
        val other = client()
        other.post("/api/v1/auth/password_reset", mapOf("email" to email)).expect(202)
        other.post("/api/v1/auth/password_reset/consume", mapOf("token" to mail.linkToken(email), "password" to "a brand new passphrase")).expect(200)

        c.get("/api/v1/me").expectError(401, "unauthenticated")
        other.get("/api/v1/me").expect(200)
        client().post("/api/v1/auth/login", mapOf("email" to email, "password" to "a brand new passphrase")).expect(200)
    }

    @Test
    fun `state-changing requests without the CSRF header are rejected`() {
        val c = signup()
        c.sendCsrf = false
        c.patch("/api/v1/me", mapOf("name" to "X")).expectError(403, "csrf_failed")
        c.sendCsrf = true
        c.patch("/api/v1/me", mapOf("name" to "X")).expect(200)
    }

    @Test
    fun `the CSRF token stays stable across authenticated requests`() {
        val c = signup()
        c.get("/api/v1/me").expect(200)
        val before = c.cookies["XSRF-TOKEN"]!!.value
        c.patch("/api/v1/me", mapOf("name" to "Y")).expect(200)
        c.get("/api/v1/account").expect(200)
        assertThat(c.cookies["XSRF-TOKEN"]!!.value).isEqualTo(before)
    }

    @Test
    fun `health endpoint reports status`() {
        val res = client().get("/actuator/health/readiness").expect(200)
        assertThat(res["status"].asText()).isEqualTo("UP")
    }

    @Test
    fun `security headers are set`() {
        val res = client().get("/api/v1/auth/config").expect(200)
        assertThat(res.headers["Content-Security-Policy"]!!.first()).contains("script-src 'self'").contains("frame-ancestors 'none'")
        assertThat(res.headers["X-Frame-Options"]!!.first()).isEqualTo("DENY")
    }

    @Test
    fun `API tokens authenticate without cookies and respect read-only scope`() {
        val c = signup()
        val created = c.post("/api/v1/me/api_tokens", mapOf("name" to "CLI", "scopes" to listOf("read"))).expect(201)
        val token = created["token"].asText()
        assertThat(token).startsWith("hrt_")

        val api = client().apply { bearer = token }
        assertThat(api.get("/api/v1/me").expect(200)["current_account_id"].asText()).isEqualTo(c.accountId.toString())
        api.patch("/api/v1/me", mapOf("name" to "Nope")).expectError(403, "insufficient_scope")

        val listed = c.get("/api/v1/me/api_tokens").expect(200)
        assertThat(listed.body.map { it["token_hint"].asText() }).containsExactly(token.takeLast(4))

        c.delete("/api/v1/me/api_tokens/${created["api_token"]["id"].asText()}").expect(204)
        api.get("/api/v1/me").expectError(401, "invalid_token")
    }

    @Test
    fun `users cannot address accounts they do not belong to`() {
        val a = signup()
        val b = signup()
        b.accountId = a.accountId
        b.get("/api/v1/account").expectError(403, "not_a_member")
    }
}
