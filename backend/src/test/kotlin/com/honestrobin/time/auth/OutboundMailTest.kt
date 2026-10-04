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

    @Autowired lateinit var reminders: com.honestrobin.time.invoicing.InvoiceReminderService

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
            // But not the same person's second workspace (security review, 4 October 2026).
            val second = admin.post("/api/v1/accounts", mapOf("name" to "Second Studio", "timezone" to "Europe/Zagreb", "default_currency" to "EUR")).expect(201)["id"].asText()
            admin.accountId = java.util.UUID.fromString(second)
            admin.post("/api/v1/people", mapOf("name" to "Four", "email" to uniqueEmail("four"), "role" to "member")).expectError(429, "invite_limit")
        } finally {
            limits.invitesPerDay = 0
            limits.inviteResendCooldown = Duration.ZERO
        }
    }

    @Test
    fun `invoice emails count per account and per person, reminders included`() {
        val admin = signup()
        limits.invoiceRecipientsPerDay = 3
        try {
            // Only the reminder's own invoice falls due today.
            fun invoice(c: com.honestrobin.time.support.TestClient, termsDays: Int = 30): java.util.UUID {
                val client = createClient(c)
                return c.post("/api/v1/invoices", mapOf("client_id" to client, "payment_terms_days" to termsDays, "lines" to listOf(mapOf("description" to "Work", "quantity" to 1, "unit_price" to 10_000)))).expect(201).id()
            }
            admin.post("/api/v1/invoices/${invoice(admin)}/send", mapOf("to" to listOf("a@client.test", "b@client.test"))).expect(200)
            // A second workspace of the same person doesn't bring a fresh allowance.
            val second = admin.post("/api/v1/accounts", mapOf("name" to "Second Studio", "timezone" to "Europe/Zagreb", "default_currency" to "EUR")).expect(201)["id"].asText()
            val first = admin.accountId!!
            admin.accountId = java.util.UUID.fromString(second)
            admin.post("/api/v1/invoices/${invoice(admin)}/send", mapOf("to" to listOf("c@client.test", "d@client.test"))).expectError(429, "invoice_email_limit")
            admin.post("/api/v1/invoices/${invoice(admin)}/send", mapOf("to" to listOf("c@client.test"))).expect(200)

            // A reminder is an invoice email too: over the account's limit, it waits.
            admin.accountId = first
            val due = invoice(admin, termsDays = 0)
            admin.post("/api/v1/invoices/$due/send", mapOf("to" to listOf("e@client.test", "f@client.test"))).expectError(429, "invoice_email_limit")
            admin.post("/api/v1/invoices/$due/mark_sent").expect(200)
            tx.system { dsl.update(com.honestrobin.time.db.Tables.INVOICES).set(com.honestrobin.time.db.Tables.INVOICES.SENT_TO, arrayOf("e@client.test", "f@client.test")).where(com.honestrobin.time.db.Tables.INVOICES.ID.eq(due)).execute() }
            fun remind() = tx.run { com.honestrobin.time.platform.db.DbContext.forAccount(first) { reminders.remindAccount(first, setOf(0)) } }
            assertThat(remind()).isEqualTo(0)
            assertThat(mail.to("e@client.test")).isEmpty()
            limits.invoiceRecipientsPerDay = 10
            assertThat(remind()).isEqualTo(1)
            assertThat(mail.to("e@client.test")).hasSize(1)
        } finally {
            limits.invoiceRecipientsPerDay = 0
        }
    }

    @Test
    fun `where anyone can sign up, the limits default to Honest Robin Cloud's`() {
        val open = MailLimits().effective(openSignup = true)
        assertThat(open.invitesPerDay).isEqualTo(50)
        assertThat(open.inviteResendCooldown).isEqualTo(Duration.ofMinutes(10))
        assertThat(open.invoiceRecipientsPerDay).isEqualTo(300)
        assertThat(MailLimits().effective(openSignup = false)).isEqualTo(MailLimits.Effective(0, Duration.ZERO, 0))
        // A setting always wins, 0 included.
        assertThat(MailLimits().apply { invoiceRecipientsPerDay = 0 }.effective(openSignup = true).invoiceRecipientsPerDay).isEqualTo(0)
    }
}
