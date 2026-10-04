// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.auth

import com.honestrobin.time.db.Tables.RATE_LIMIT_EVENTS
import com.honestrobin.time.db.Tables.USER_SESSIONS
import com.honestrobin.time.support.IntegrationTest
import com.honestrobin.time.support.MockPwned
import com.honestrobin.time.support.TestClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** Security review, 3 October: recent sign-in for sensitive actions, session lifetime, password checks. */
class SessionSecurityTest : IntegrationTest() {

    private fun ageSignIn(c: TestClient, minutes: Long) = tx.system {
        dsl.update(USER_SESSIONS).set(USER_SESSIONS.AUTHENTICATED_AT, Instant.now().minus(Duration.ofMinutes(minutes)))
            .where(USER_SESSIONS.USER_ID.eq(c.userId)).execute()
    }

    @Test
    fun `sensitive actions need a recent sign-in, and the password brings it back`() {
        val admin = signup(name = "Rangi Admin", accountName = "Kauri Works")
        // Just signed in: allowed.
        admin.post("/api/v1/me/api_tokens", mapOf("name" to "CLI", "scopes" to listOf("read"))).expect(201)

        ageSignIn(admin, 11)
        admin.post("/api/v1/me/api_tokens", mapOf("name" to "CLI 2", "scopes" to listOf("read"))).expectError(403, "reauth_required")
        admin.post("/api/v1/account/deletion", mapOf("confirm_name" to "Kauri Works")).expectError(403, "reauth_required")
        admin.post("/api/v1/exports").expectError(403, "reauth_required")
        admin.patch("/api/v1/account", mapOf("iban" to "GB33BUKB20201555555555")).expectError(403, "reauth_required")
        admin.patch("/api/v1/invoice_settings", mapOf("payment_instructions" to "Pay to my other account")).expectError(403, "reauth_required")
        admin.post("/api/v1/payments/stripe/connect").expectError(403, "reauth_required")
        // Everything else carries on, including saving bank details unchanged.
        admin.patch("/api/v1/account", mapOf("name" to "Kauri Works Ltd")).expect(200)
        admin.patch("/api/v1/account", mapOf("iban" to "")).expect(200)

        admin.post("/api/v1/auth/reauth", mapOf("password" to "not my password")).expectError(400, "invalid_password")
        admin.post("/api/v1/auth/reauth", mapOf("password" to "correct horse battery")).expect(204)
        admin.post("/api/v1/me/api_tokens", mapOf("name" to "CLI 2", "scopes" to listOf("read"))).expect(201)
        admin.patch("/api/v1/account", mapOf("iban" to "GB33 BUKB 2020 1555 5555 55")).expect(200)
    }

    @Test
    fun `API tokens may export, but never change where money goes or delete the account`() {
        val admin = signup(accountName = "Token Works")
        val token = admin.post("/api/v1/me/api_tokens", mapOf("name" to "Backups", "scopes" to listOf("read", "write"))).expect(201)["token"].asText()
        val api = client().apply { bearer = token }
        api.post("/api/v1/exports").expect(202)
        // A token can be phished through device sign-in (security review, 4 October 2026).
        api.patch("/api/v1/account", mapOf("iban" to "DE89370400440532013000")).expectError(403, "forbidden")
        api.patch("/api/v1/invoice_settings", mapOf("payment_instructions" to "Pay to my other account")).expectError(403, "forbidden")
        api.patch("/api/v1/account", mapOf("name" to "Token Works Ltd")).expect(200)
        api.post("/api/v1/account/deletion", mapOf("confirm_name" to "Token Works")).expectError(403, "forbidden")
        api.post("/api/v1/auth/reauth", mapOf("password" to "correct horse battery")).expectError(403, "forbidden")
    }

    @Test
    fun `an invoice's own payment instructions follow the same rule as the account's`() {
        val admin = signup()
        val client = createClient(admin)
        val invoice = admin.post("/api/v1/invoices", mapOf("client_id" to client, "lines" to listOf(mapOf("description" to "Work", "quantity" to 1, "unit_price" to 10_000)))).expect(201)
        val id = invoice.id()
        val invoicer = invite(admin, role = "manager", extra = mapOf("can_manage_invoices" to true))
        // A manager who may invoice can edit the invoice, but not where it says to pay.
        invoicer.patch("/api/v1/invoices/$id", mapOf("subject" to "September")).expect(200)
        invoicer.patch("/api/v1/invoices/$id", mapOf("payment_instructions" to "Pay to my own account")).expectError(403, "forbidden")
        invoicer.post("/api/v1/invoices", mapOf("client_id" to client, "payment_instructions" to "Pay to my own account")).expectError(403, "forbidden")
        // Saving them unchanged is fine.
        invoicer.patch("/api/v1/invoices/$id", mapOf("payment_instructions" to invoice["payment_instructions"].textValue())).expect(200)
        // An admin needs a recent sign-in.
        ageSignIn(admin, 11)
        admin.patch("/api/v1/invoices/$id", mapOf("payment_instructions" to "New bank")).expectError(403, "reauth_required")
        admin.post("/api/v1/auth/reauth", mapOf("password" to "correct horse battery")).expect(204)
        admin.patch("/api/v1/invoices/$id", mapOf("payment_instructions" to "New bank")).expect(200)
    }

    @Test
    fun `changing the password ends earlier sign-in links and tells the person`() {
        val admin = signup()
        client().post("/api/v1/auth/magic_link", mapOf("email" to admin.email)).expect(202)
        val link = mail.linkToken(admin.email!!)
        admin.post("/api/v1/me/password", mapOf("current_password" to "correct horse battery", "new_password" to "a brand new passphrase")).expect(204)
        assertThat(mail.lastTo(admin.email!!).text).contains("Your password was changed")
        client().post("/api/v1/auth/magic_link/consume", mapOf("token" to link)).expectError(400, "invalid_link")
    }

    @Test
    fun `setting a first password needs a recent sign-in`() {
        val admin = signup()
        // A person who signs in with links only has no password to confirm.
        tx.system { dsl.execute("update users set password_hash = null where id = ?", admin.userId) }
        admin.post("/api/v1/auth/reauth", mapOf("password" to "anything at all")).expectError(409, "no_password")
        ageSignIn(admin, 30)
        admin.post("/api/v1/me/password", mapOf("new_password" to "a brand new passphrase")).expectError(403, "reauth_required")
        ageSignIn(admin, 0)
        admin.post("/api/v1/me/password", mapOf("new_password" to "a brand new passphrase")).expect(204)
    }

    @Test
    fun `sessions end 90 days after sign-in, however much they are used`() {
        val admin = signup()
        admin.get("/api/v1/me").expect(200)
        tx.system {
            dsl.update(USER_SESSIONS).set(USER_SESSIONS.CREATED_AT, Instant.now().minus(Duration.ofDays(91)))
                .where(USER_SESSIONS.USER_ID.eq(admin.userId)).execute()
        }
        admin.get("/api/v1/me").expect(401)
    }

    @Test
    fun `passwords that are common, simple, built from your name, or breached are refused`() {
        fun attempt(password: String, name: String = "Marta Owner", email: String = uniqueEmail("marta")) =
            client().post("/api/v1/auth/signup", mapOf("name" to name, "email" to email, "password" to password, "account_name" to "Fjord Studio"))

        assertThat(attempt("password1234").expectError(422, "validation_failed").body["fields"]["password"].asText()).contains("most used")
        assertThat(attempt("1234567890").expectError(422, "validation_failed").body["fields"]["password"].asText()).contains("too easy")
        assertThat(attempt("zzzzzzzzzzzz").expectError(422, "validation_failed").body["fields"]["password"].asText()).contains("too easy")
        assertThat(attempt("MartaOwner2026").expectError(422, "validation_failed").body["fields"]["password"].asText()).contains("your name")
        assertThat(attempt("fjordstudio!!").expectError(422, "validation_failed").body["fields"]["password"].asText()).contains("workspace")
        assertThat(attempt("breached but long enough").expectError(422, "validation_failed").body["fields"]["password"].asText()).contains("data breach")
        // Only five characters of the hash leave the server.
        assertThat(MockPwned.requests).isPositive()

        // A long passphrase that happens to contain the name is fine.
        attempt("marta likes kayaking at dawn").expect(201)
        // If the breach service is down, a password is accepted rather than blocking sign-up.
        MockPwned.failing = true
        try {
            attempt("Tr0ub4dor&3-leaked").expect(201)
        } finally {
            MockPwned.failing = false
        }
    }

    @Test
    fun `a refused password at reset keeps the link usable`() {
        val admin = signup()
        client().post("/api/v1/auth/password_reset", mapOf("email" to admin.email)).expect(202)
        val token = mail.linkToken(admin.email!!)
        client().post("/api/v1/auth/password_reset/consume", mapOf("token" to token, "password" to "breached but long enough")).expectError(422, "validation_failed")
        client().post("/api/v1/auth/password_reset/consume", mapOf("token" to token, "password" to "a brand new passphrase")).expect(200)
    }

    @Test
    fun `failed sign-ins are counted in the database, so every server agrees, and lock the address after ten`() {
        val admin = signup()
        repeat(10) {
            client().post("/api/v1/auth/login", mapOf("email" to admin.email, "password" to "not the password")).expectError(401, "invalid_credentials")
        }
        val counted = tx.system {
            dsl.fetchCount(RATE_LIMIT_EVENTS, RATE_LIMIT_EVENTS.BUCKET.eq("login:email:${admin.email}"))
        }
        assertThat(counted).isEqualTo(10)
        client().post("/api/v1/auth/login", mapOf("email" to admin.email, "password" to "correct horse battery")).expectError(429, "too_many_attempts")
        // Other addresses are unaffected.
        val other = signup()
        client().post("/api/v1/auth/login", mapOf("email" to other.email, "password" to "correct horse battery")).expect(200)
    }
}

