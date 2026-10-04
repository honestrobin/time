// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.auth

import com.honestrobin.time.db.Tables.RATE_LIMIT_EVENTS
import com.honestrobin.time.platform.crypto.Base32
import com.honestrobin.time.platform.crypto.Totp
import com.honestrobin.time.support.IntegrationTest
import com.honestrobin.time.support.TestClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/** Two-factor sign-in with an authenticator app (security review, 3 October). */
class TwoFactorTest : IntegrationTest() {

    /** Turns on two-factor sign-in for [c]; returns the secret, the step already used, and the recovery codes. */
    private fun enable(c: TestClient): Triple<String, Long, List<String>> {
        val setup = c.post("/api/v1/me/two_factor/setup").expect(200)
        val secret = setup["secret"].asText().replace(" ", "")
        assertThat(setup["otpauth_uri"].asText()).startsWith("otpauth://totp/Honest%20Robin:").contains("secret=$secret")
        assertThat(setup["qr_svg"].asText()).startsWith("<svg")
        val valid = (-1..1).map { Totp.code(secret, Totp.step(Instant.now()) + it) }
        c.post("/api/v1/me/two_factor/enable", mapOf("code" to listOf("000000", "111111", "222222").first { it !in valid })).expectError(422, "validation_failed")
        val step = Totp.step(Instant.now())
        val codes = c.post("/api/v1/me/two_factor/enable", mapOf("code" to Totp.code(secret, step))).expect(200)["recovery_codes"].map { it.asText() }
        assertThat(codes).hasSize(10).allMatch { it.matches(Regex("[a-z2-9]{5}-[a-z2-9]{5}")) }
        return Triple(secret, step, codes)
    }

    /** Signs in with the password; returns the client and the challenge waiting for the code. */
    private fun login(email: String): Pair<TestClient, String> {
        val c = client()
        val res = c.post("/api/v1/auth/login", mapOf("email" to email, "password" to "correct horse battery")).expect(200)
        assertThat(res["two_factor_required"].asBoolean()).isTrue()
        assertThat(c.cookies.keys).doesNotContain("honestrobin_session")
        return c to res["challenge"].asText()
    }

    @Test
    fun `sign-in asks for the app's code once two-factor is on, and each code works once`() {
        val admin = signup(name = "Wiremu Admin")
        assertThat(admin.get("/api/v1/me/two_factor").expect(200)["enabled"].asBoolean()).isFalse()
        val (secret, step, recovery) = enable(admin)
        assertThat(mail.lastTo(admin.email!!).text).contains("Two-factor sign-in is now on")
        assertThat(admin.get("/api/v1/me").expect(200)["two_factor_enabled"].asBoolean()).isTrue()

        // Password alone no longer signs in: a challenge waits for the code.
        val (c, challenge) = login(admin.email!!)
        c.get("/api/v1/me").expect(401)
        c.post("/api/v1/auth/two_factor", mapOf("challenge" to challenge, "code" to Totp.code(secret, step))).expectError(400, "invalid_code") // already used
        c.post("/api/v1/auth/two_factor", mapOf("challenge" to challenge, "code" to "12345")).expectError(400, "invalid_code")
        c.post("/api/v1/auth/two_factor", mapOf("challenge" to challenge, "code" to Totp.code(secret, step + 1))).expect(200)
        c.get("/api/v1/me").expect(200)
        // The challenge is spent.
        client().post("/api/v1/auth/two_factor", mapOf("challenge" to challenge, "code" to Totp.code(secret, step + 1))).expectError(400, "challenge_expired")

        // A recovery code works once, and the person hears about it.
        val (r, rChallenge) = login(admin.email!!)
        r.post("/api/v1/auth/two_factor", mapOf("challenge" to rChallenge, "recovery_code" to recovery[0].uppercase())).expect(200)
        assertThat(mail.lastTo(admin.email!!).text).contains("recovery code was used").contains("9 left")
        val (again, againChallenge) = login(admin.email!!)
        again.post("/api/v1/auth/two_factor", mapOf("challenge" to againChallenge, "recovery_code" to recovery[0])).expectError(400, "invalid_code")
        assertThat(admin.get("/api/v1/me/two_factor").expect(200)["recovery_codes_left"].asInt()).isEqualTo(9)

        // A sign-in link proves the inbox, not the second factor.
        client().post("/api/v1/auth/magic_link", mapOf("email" to admin.email)).expect(202)
        val viaLink = client()
        val res = viaLink.post("/api/v1/auth/magic_link/consume", mapOf("token" to mail.linkToken(admin.email!!))).expect(200)
        assertThat(res["two_factor_required"].asBoolean()).isTrue()
        viaLink.post("/api/v1/auth/two_factor", mapOf("challenge" to res["challenge"].asText(), "recovery_code" to recovery[1])).expect(200)
        viaLink.get("/api/v1/me").expect(200)

        // Turning it off needs a recent sign-in, and is confirmed by email.
        admin.delete("/api/v1/me/two_factor").expect(204)
        assertThat(mail.lastTo(admin.email!!).text).contains("turned off")
        client().post("/api/v1/auth/login", mapOf("email" to admin.email, "password" to "correct horse battery")).expect(200)
            .also { assertThat(it["two_factor_required"].asBoolean()).isFalse() }
        assertThat(Base32.decode(secret)).hasSize(20)
    }

    @Test
    fun `an account can require two-factor sign-in of everyone`() {
        val admin = signup(accountName = "Harakeke Design")
        val member = invite(admin)
        admin.patch("/api/v1/account", mapOf("require_two_factor" to true)).expectError(409, "enable_two_factor_first")
        enable(admin)
        admin.patch("/api/v1/account", mapOf("require_two_factor" to true)).expect(200)

        // Until the member sets it up, only setting it up works.
        member.get("/api/v1/time_entries").expectError(403, "two_factor_required")
        assertThat(member.get("/api/v1/me").expect(200)["two_factor_setup_required"].asBoolean()).isTrue()
        val people = admin.get("/api/v1/people").expect(200)["data"]
        assertThat(people.first { it["email"].asText() == member.email }["two_factor_enabled"].asBoolean()).isFalse()
        enable(member)
        member.get("/api/v1/time_entries").expect(200)
        assertThat(member.get("/api/v1/me").expect(200)["two_factor_setup_required"].asBoolean()).isFalse()
        member.delete("/api/v1/me/two_factor").expectError(409, "required_by_account")
        assertThat(admin.get("/api/v1/people").expect(200)["data"].first { it["email"].asText() == member.email }["two_factor_enabled"].asBoolean()).isTrue()
    }

    @Test
    fun `the owner of an unconfirmed address clears a stranger's two-factor sign-in`() {
        // Someone signs up with an address that isn't theirs and turns on two-factor sign-in.
        val stranger = signup(verifyEmail = false)
        enable(stranger)
        // The real owner proves the inbox with a sign-in link and gets in without the stranger's app.
        client().post("/api/v1/auth/magic_link", mapOf("email" to stranger.email)).expect(202)
        val owner = client()
        val res = owner.post("/api/v1/auth/magic_link/consume", mapOf("token" to mail.linkToken(stranger.email!!))).expect(200)
        assertThat(res["two_factor_required"].asBoolean()).isFalse()
        assertThat(owner.get("/api/v1/me/two_factor").expect(200)["enabled"].asBoolean()).isFalse()
    }

    @Test
    fun `wrong codes are limited per person, and signing in again with the password doesn't reset the count`() {
        val admin = signup()
        val (secret, step, _) = enable(admin)
        val near = (-2..2).map { Totp.code(secret, Totp.step(Instant.now()) + it) }
        val wrong = listOf("000000", "111111", "222222", "333333").first { it !in near }
        // Every test signs in from 127.0.0.1: start from, and leave, a clean count for that address.
        val clearIp = { tx.system { dsl.deleteFrom(RATE_LIMIT_EVENTS).where(RATE_LIMIT_EVENTS.BUCKET.eq("login:ip:127.0.0.1")).execute() } }
        clearIp()
        try {
            // A challenge is used up after five wrong codes.
            val (c, challenge) = login(admin.email!!)
            repeat(LoginThrottle.WRONG_CODES_PER_CHALLENGE) {
                c.post("/api/v1/auth/two_factor", mapOf("challenge" to challenge, "code" to wrong)).expectError(400, "invalid_code")
            }
            c.post("/api/v1/auth/two_factor", mapOf("challenge" to challenge, "code" to Totp.code(secret, step + 1))).expectError(400, "challenge_expired")
            // Whoever got this far knows the password, and its owner hears about it.
            assertThat(mail.lastTo(admin.email!!).text).contains("then a wrong two-factor code 5 times")

            // The password brings a new challenge, but no new guesses beyond the limit.
            val (d, next) = login(admin.email!!)
            repeat(LoginThrottle.SECOND_FACTOR_PER_WINDOW - LoginThrottle.WRONG_CODES_PER_CHALLENGE) {
                d.post("/api/v1/auth/two_factor", mapOf("challenge" to next, "code" to wrong)).expectError(400, "invalid_code")
            }
            val (e, last) = login(admin.email!!)
            e.post("/api/v1/auth/two_factor", mapOf("challenge" to last, "code" to Totp.code(secret, step + 1))).expectError(429, "too_many_attempts")
        } finally {
            clearIp()
        }
    }

    @Test
    fun `codes follow RFC 6238`() {
        // RFC 6238 appendix B, SHA-1 secret "12345678901234567890", truncated to six digits.
        val secret = Base32.encode("12345678901234567890".toByteArray())
        assertThat(Totp.code(secret, 59L / 30)).isEqualTo("287082")
        assertThat(Totp.code(secret, 1111111109L / 30)).isEqualTo("081804")
        assertThat(Totp.code(secret, 20000000000L / 30)).isEqualTo("353130")
        assertThat(Totp.verify(secret, "287 082", Instant.ofEpochSecond(59), null)).isEqualTo(1L)
        assertThat(Totp.verify(secret, "287082", Instant.ofEpochSecond(59), 1L)).isNull()
    }
}
