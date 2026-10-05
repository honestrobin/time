// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform

import com.honestrobin.time.db.Tables.ACCOUNTS
import com.honestrobin.time.support.IntegrationTest
import com.honestrobin.time.support.TestClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Findings of the security review of 3 October 2026, each kept fixed by a test. */
class SecurityRegressionTest : IntegrationTest() {

    @Test
    fun `an invitation link stops working when the person's email changes, so it can't sign in as someone else`() {
        val victim = signup(name = "Vic Victim")
        val attacker = signup(accountName = "Evil Co")
        val person = attacker.post("/api/v1/people", mapOf("name" to "Pat", "email" to uniqueEmail("pat"), "role" to "member")).expect(201)
        val token = mail.linkToken(person["email"].asText())
        attacker.patch("/api/v1/people/${person.id()}", mapOf("email" to victim.email)).expect(200)
        assertThat(attacker.get("/api/v1/people/${person.id()}")["status"].asText()).isEqualTo("pending_invite")
        client().post("/api/v1/auth/invite/lookup", mapOf("token" to token)).expectError(400, "invalid_link")
        client().post("/api/v1/auth/invite/accept", mapOf("token" to token)).expectError(400, "invalid_link")
    }

    @Test
    fun `whoever proves an unconfirmed address takes it over cleanly, and the person who typed it loses access`() {
        val email = uniqueEmail("cfo")
        // Someone registers an address that isn't theirs and keeps a password and a session.
        val squatter = signup(email = email, verifyEmail = false)
        assertThat(squatter.get("/api/v1/me").status).isEqualTo(200)
        // The real owner is invited elsewhere and accepts from their own inbox.
        val company = signup(accountName = "Target Ltd")
        company.post("/api/v1/people", mapOf("name" to "CFO", "email" to email, "role" to "admin")).expect(201)
        val owner = TestClient(mockMvc, mapper)
        owner.post("/api/v1/auth/invite/accept", mapOf("token" to mail.linkToken(email))).expect(200)
        owner.accountId = company.accountId
        assertThat(owner.get("/api/v1/me").expect(200)["email_verified"].asBoolean()).isTrue()
        // The squatter's session and password are gone.
        assertThat(squatter.get("/api/v1/me").status).isEqualTo(401)
        client().post("/api/v1/auth/login", mapOf("email" to email, "password" to "correct horse battery")).expectError(401, "invalid_credentials")
    }

    @Test
    fun `claiming an address with a sign-in link also removes the API tokens of whoever typed it`() {
        // Security review of 4 October 2026: the delete ran under row-level security with no
        // account set, so it matched nothing and the squatter's token kept working.
        val squatter = signup(verifyEmail = false)
        val token = squatter.post("/api/v1/me/api_tokens", mapOf("name" to "Keep", "scopes" to listOf("read", "write"))).expect(201)["token"].asText()
        val api = client().apply { bearer = token }
        api.get("/api/v1/me").expect(200)

        client().post("/api/v1/auth/magic_link", mapOf("email" to squatter.email)).expect(202)
        client().post("/api/v1/auth/magic_link/consume", mapOf("token" to mail.linkToken(squatter.email!!))).expect(200)
        api.get("/api/v1/me").expectError(401, "invalid_token")
        assertThat(mail.lastTo(squatter.email!!).text).contains("whoever set it up before can't sign in any more")
    }

    @Test
    fun `opening a public invoice signed in leaves no trace of the visitor in the seller's audit log`() {
        // Security review, 4 October 2026: the view counter was written with the visitor's name and IP.
        val seller = signup(accountName = "Seller Co")
        val client = createClient(seller)
        val inv = seller.post("/api/v1/invoices", mapOf("client_id" to client, "lines" to listOf(mapOf("description" to "Work", "quantity" to 1, "unit_price" to 10_000)))).expect(201).id()
        val token = seller.post("/api/v1/invoices/$inv/mark_sent").expect(200)["public_url"].asText().substringAfterLast("/")
        val visitor = signup(accountName = "Visitor Co")
        val res = visitor.get("/api/v1/public/invoices/$token").expect(200)
        // Search engines are told to stay out.
        assertThat(res.headers["X-Robots-Tag"]).contains("noindex, nofollow")
        val entries = seller.get("/api/v1/audit_log", mapOf("entity_type" to "invoices")).expect(200)["entries"]
        assertThat(entries.values().map { it["actor_user_id"].asText() }).doesNotContain(visitor.userId.toString())
    }

    @Test
    fun `receipts are stored as what their bytes are and only images and PDFs are shown in the browser`() {
        val admin = signup()
        val task = createTask(admin)
        val project = createProject(admin, taskIds = listOf(task)).id()
        val category = admin.post("/api/v1/expense_categories", mapOf("name" to "Travel")).expect(201).id()
        val expense = admin.post("/api/v1/expenses", mapOf("project_id" to project, "category_id" to category, "spent_date" to "2026-09-15", "amount" to 100)).expect(201).id()
        // HTML claiming to be a PDF is refused.
        admin.upload("/api/v1/expenses/$expense/receipt", "file", "x.pdf", "application/pdf", "<script>alert(1)</script>".toByteArray()).expectError(422, "validation_failed")
        // A real PDF labelled as a PNG is kept, as a PDF.
        admin.upload("/api/v1/expenses/$expense/receipt", "file", "scan.png", "image/png", "%PDF-1.4 receipt".toByteArray()).expect(200)
        val res = admin.get("/api/v1/expenses/$expense/receipt").expect(200)
        assertThat(res.headers["Content-Type"]!!.single()).startsWith("application/pdf")
        assertThat(res.headers["X-Content-Type-Options"]!!.single()).isEqualTo("nosniff")
        assertThat(res.headers["Content-Security-Policy"]!!.single()).contains("default-src 'none'")
    }

    @Test
    fun `a lapsed account can't create invoices, teams or categories`() {
        val admin = signup()
        val client = createClient(admin)
        tx.system { dsl.update(ACCOUNTS).set(ACCOUNTS.STATUS, "lapsed").set(ACCOUNTS.LAPSED_AT, Instant.now()).where(ACCOUNTS.ID.eq(admin.accountId)).execute() }
        admin.post("/api/v1/invoices", mapOf("client_id" to client, "lines" to listOf(mapOf("description" to "Work", "quantity" to 1, "unit_price" to 100)))).expectError(402, "account_read_only")
        admin.post("/api/v1/teams", mapOf("name" to "Team")).expectError(402, "account_read_only")
        admin.post("/api/v1/expense_categories", mapOf("name" to "Meals")).expectError(402, "account_read_only")
    }

    @Test
    fun `ids from another account are refused, though foreign keys would allow them`() {
        val other = signup()
        val otherPerson = membershipId(other)
        val admin = signup()
        admin.post("/api/v1/teams", mapOf("name" to "Mixed", "membership_ids" to listOf(otherPerson))).expectError(422, "validation_failed")
        admin.post("/api/v1/projects", mapOf("client_id" to createClient(admin), "name" to "P", "membership_ids" to listOf(otherPerson))).expectError(422, "validation_failed")
        val otherProject = createProject(other).id()
        admin.post("/api/v1/invoices", mapOf("client_id" to createClient(admin), "lines" to listOf(mapOf("description" to "x", "quantity" to 1, "unit_price" to 1, "project_id" to otherProject))))
            .expectError(422, "validation_failed")
    }

    /**
     * Found on 5 October 2026 by a change that makes jOOQ refuse a query outside a transaction:
     * since 4 October, editing an invoice ran without one, so as the database user, which
     * row-level security doesn't apply to in the Compose setup. Someone allowed to invoice in one
     * account could read and change another account's invoice by its id (an admin, its payment
     * instructions too), and a refused edit kept what it had saved before the refusal.
     */
    @Test
    fun `another account's invoice can't be read or changed by its id, and a refused edit changes nothing`() {
        val owner = signup(accountName = "Owner")
        val created = owner.post("/api/v1/invoices", mapOf("client_id" to createClient(owner), "subject" to "September", "lines" to listOf(mapOf("description" to "Work", "quantity" to 1, "unit_price" to 10_000))))
            .expect(201)
        val id = created.id()
        val outsider = signup(accountName = "Outsider")
        outsider.get("/api/v1/invoices/$id").expectError(404, "not_found")
        outsider.patch("/api/v1/invoices/$id", mapOf("subject" to "Changed from another account")).expectError(404, "not_found")
        outsider.patch("/api/v1/invoices/$id", mapOf("payment_instructions" to "Pay to another account")).expectError(404, "not_found")
        val untouched = owner.get("/api/v1/invoices/$id").expect(200)
        assertThat(untouched["subject"].asText()).isEqualTo("September")
        assertThat(untouched["payment_instructions"]).isEqualTo(created["payment_instructions"])

        // A refused edit is refused whole: the new total would be less than what's already paid.
        owner.post("/api/v1/invoices/$id/mark_sent").expect(200)
        owner.post("/api/v1/invoices/$id/payments", mapOf("amount" to 5_000)).expect(201)
        owner.patch("/api/v1/invoices/$id", mapOf("subject" to "Smaller", "lines" to listOf(mapOf("description" to "Less", "quantity" to 1, "unit_price" to 1_000))))
            .expectError(422, "validation_failed")
        val after = owner.get("/api/v1/invoices/$id").expect(200)
        assertThat(after["subject"].asText()).isEqualTo("September")
        assertThat(after["lines"].values().map { it["description"].asText() }).containsExactly("Work")
        assertThat(after["total"].asLong()).isEqualTo(untouched["total"].asLong())
    }

    @Test
    fun `malformed cursors are a bad request, not a server error`() {
        val admin = signup()
        admin.get("/api/v1/time_entries", mapOf("cursor" to "nonsense")).expectError(400, "bad_cursor")
        admin.get("/api/v1/people", mapOf("cursor" to "nonsense")).expectError(400, "bad_cursor")
        admin.get("/api/v1/invoices", mapOf("cursor" to "2026-01-01_nonsense")).expectError(400, "bad_cursor")
    }

    @Test
    fun `metrics need the operator's token`() {
        assertThat(client().get("/actuator/prometheus").status).isIn(401, 403)
    }

    @Test
    fun `names that go into emails can't carry links`() {
        val admin = signup()
        admin.post("/api/v1/people", mapOf("name" to "Win a prize at https://spam.example", "email" to uniqueEmail("x"), "role" to "member", "send_invite" to false))
            .expectError(422, "validation_failed")
        admin.patch("/api/v1/me", mapOf("name" to "www.spam.example")).expectError(422, "validation_failed")
    }

    @Test
    fun `an export that unpacks far beyond its size is refused while reading`() {
        val c = signup()
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z ->
            z.putNextEntry(ZipEntry("manifest.json"))
            z.write("""{"format":"honestrobin-export","version":1,"account_id":"00000000-0000-7000-8000-00000000abcd","tables":[]}""".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("data/accounts.json"))
            val chunk = ByteArray(1 shl 20) { ' '.code.toByte() }
            repeat(300) { z.write(chunk) } // 300 MB of spaces, about 300 KB zipped
            z.closeEntry()
        }
        c.request(HttpMethod.POST, "/api/v1/accounts/import", bytes.toByteArray(), headers = mapOf("Content-Type" to "application/zip"))
            .expectError(400, "export_too_large")
    }

    @Test
    fun `the database itself refuses a reference into another account`() {
        val a = signup(accountName = "Account A")
        val b = signup(accountName = "Account B")
        val project = createProject(a).id()
        val foreignClient = createClient(b)
        // Even with the application and row-level security out of the way (system context), the
        // keys include account_id, so A's project can't name B's client.
        val error = runCatching {
            tx.system { dsl.execute("update projects set client_id = ? where id = ?", foreignClient, project) }
        }.exceptionOrNull()
        assertThat(generateSequence(error) { it.cause }.filterIsInstance<java.sql.SQLException>().firstOrNull()?.sqlState).isEqualTo("23503")
        // Its own client is fine.
        tx.system { dsl.execute("update projects set client_id = ? where id = ?", createClient(a), project) }
    }
}
