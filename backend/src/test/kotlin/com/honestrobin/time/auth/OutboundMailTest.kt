// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.auth

import com.honestrobin.time.platform.mail.MailLimits
import com.honestrobin.time.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration

/** Decision record 0012: confirmed senders, and limits on invitation emails. */
class OutboundMailTest : IntegrationTest() {
    @Autowired lateinit var limits: MailLimits

    @Test
    fun `with open sign-up an account emails nobody until an admin confirms their address`() {
        val admin = signup(verifyEmail = false)
        val me = admin.get("/api/v1/me").expect(200)
        assertThat(me["email_verified"].asBoolean()).isFalse()
        assertThat(me["email_verification_required"].asBoolean()).isTrue()
        assertThat(mail.lastTo(admin.email!!).subject).isEqualTo("Confirm your email address")
        val firstLink = mail.linkToken(admin.email!!)

        admin.post("/api/v1/people", mapOf("name" to "Mo", "email" to uniqueEmail("mo"), "role" to "member")).expectError(409, "email_unverified")
        // People can still be added; only the email waits.
        admin.post("/api/v1/people", mapOf("name" to "Mo", "email" to uniqueEmail("mo"), "role" to "member", "send_invite" to false)).expect(201)
        val client = createClient(admin)
        val invoice = admin.post("/api/v1/invoices", mapOf("client_id" to client, "lines" to listOf(mapOf("description" to "Work", "quantity" to 1, "unit_price" to 10_000)))).expect(201).id()
        admin.post("/api/v1/invoices/$invoice/send", mapOf("to" to listOf("billing@client.test"))).expectError(409, "email_unverified")
        admin.post("/api/v1/invoices/$invoice/mark_sent").expect(200)

        // Ask for the link again; either link works, once.
        admin.post("/api/v1/auth/verify_email/resend").expect(204)
        assertThat(mail.to(admin.email!!).count { it.subject == "Confirm your email address" }).isEqualTo(2)
        // Confirming needs the signed-in user who signed up, so nobody confirms someone else's sign-up for them.
        client().post("/api/v1/auth/verify_email", mapOf("token" to firstLink)).expectError(401, "sign_in_to_confirm")
        signup().post("/api/v1/auth/verify_email", mapOf("token" to firstLink)).expectError(403, "wrong_user")
        admin.post("/api/v1/auth/verify_email", mapOf("token" to firstLink)).expect(204)
        admin.post("/api/v1/auth/verify_email", mapOf("token" to firstLink)).expectError(400, "invalid_link")
        assertThat(admin.get("/api/v1/me")["email_verified"].asBoolean()).isTrue()
        admin.post("/api/v1/people", mapOf("name" to "Ana", "email" to uniqueEmail("ana"), "role" to "member")).expect(201)
        // Once confirmed, no more links are sent.
        admin.post("/api/v1/auth/verify_email/resend").expect(204)
        assertThat(mail.to(admin.email!!).count { it.subject == "Confirm your email address" }).isEqualTo(2)

        // Accepting an invitation proves the address too.
        val member = invite(admin)
        assertThat(member.get("/api/v1/me")["email_verified"].asBoolean()).isTrue()
    }

    @Test
    fun `invitations are limited when the instance sets limits, as Honest Robin Cloud does`() {
        val admin = signup()
        limits.invitesPerDay = 2
        limits.inviteResendCooldown = Duration.ofMinutes(10)
        try {
            val first = admin.post("/api/v1/people", mapOf("name" to "One", "email" to uniqueEmail("one"), "role" to "member")).expect(201).id()
            admin.post("/api/v1/people/$first/invite").expectError(429, "invite_cooldown")
            admin.post("/api/v1/people", mapOf("name" to "Two", "email" to uniqueEmail("two"), "role" to "member")).expect(201)
            admin.post("/api/v1/people", mapOf("name" to "Three", "email" to uniqueEmail("three"), "role" to "member")).expectError(429, "invite_limit")
            // Other accounts have their own allowance.
            val other = signup()
            other.post("/api/v1/people", mapOf("name" to "Elsewhere", "email" to uniqueEmail("else"), "role" to "member")).expect(201)
        } finally {
            limits.invitesPerDay = 0
            limits.inviteResendCooldown = Duration.ZERO
        }
    }
}
