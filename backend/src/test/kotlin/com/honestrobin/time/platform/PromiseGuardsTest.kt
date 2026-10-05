// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform

import com.honestrobin.time.billing.FreePlan
import com.honestrobin.time.db.Tables.SUBSCRIPTIONS
import com.honestrobin.time.platform.crypto.Totp
import com.honestrobin.time.platform.edition.Edition
import com.honestrobin.time.support.IntegrationTest
import com.honestrobin.time.support.TestClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import java.io.File
import java.time.Instant
import java.util.zip.ZipInputStream

/**
 * Promises from the Robin's Code that no single module keeps on its own: "You get the whole
 * product" and "The deal doesn't change after you move in". Each test fails if the promise breaks
 * by accident. None of them can stop it being broken on purpose.
 */
class PromiseGuardsTest : IntegrationTest() {
    @Autowired lateinit var props: HonestRobinProperties

    @Autowired lateinit var context: ApplicationContext

    @Test
    fun `the free plan has two-factor sign-in, the audit log, export and the API`() {
        assumeTrue(props.edition == Edition.CLOUD, "plans are part of Honest Robin Cloud only")
        // Tests otherwise let an account have many people for free; this is the real free plan.
        val freePlan = context.getBean(FreePlan::class.java)
        val seats = freePlan.seats
        freePlan.seats = 1
        try {
            val admin = signup(name = "Ana Solo", accountName = "Solo Studio")
            assertThat(tx.system { dsl.fetchExists(SUBSCRIPTIONS, SUBSCRIPTIONS.ACCOUNT_ID.eq(admin.accountId)) }).isFalse()
            assertThat(admin.get("/api/v1/billing/subscription").expect(200)["plan"].asText()).isEqualTo("free")

            // Two-factor sign-in, and the account may require it of everyone.
            val secret = admin.post("/api/v1/me/two_factor/setup").expect(200)["secret"].asText().replace(" ", "")
            val step = Totp.step(Instant.now())
            admin.post("/api/v1/me/two_factor/enable", mapOf("code" to Totp.code(secret, step))).expect(200)
            admin.patch("/api/v1/account", mapOf("require_two_factor" to true)).expect(200)
            val again = client()
            val login = again.post("/api/v1/auth/login", mapOf("email" to admin.email, "password" to "correct horse battery")).expect(200)
            assertThat(login["two_factor_required"].asBoolean()).isTrue()
            again.post("/api/v1/auth/two_factor", mapOf("challenge" to login["challenge"].asText(), "code" to Totp.code(secret, step + 1))).expect(200)
            again.get("/api/v1/me").expect(200)

            // The API, with a token that reads and writes.
            val token = admin.post("/api/v1/me/api_tokens", mapOf("name" to "Scripts", "scopes" to listOf("read", "write"))).expect(201)["token"].asText()
            val api = client().apply { bearer = token }
            val clientId = api.post("/api/v1/clients", mapOf("name" to "Kereru Books", "currency" to "EUR")).expect(201).id()
            assertThat(api.get("/api/v1/clients").expect(200)["data"].values().map { it["id"].asText() }).contains(clientId.toString())

            // The audit log shows both changes, and what made them.
            val accountChanges = admin.get("/api/v1/audit_log", mapOf("entity_type" to "accounts")).expect(200)["entries"]
            assertThat(accountChanges.any { it["action"].asText() == "accounts.update" && it["diff"]["require_two_factor"]?.get(1)?.asBoolean() == true }).isTrue()
            val clientChanges = admin.get("/api/v1/audit_log", mapOf("entity_type" to "clients")).expect(200)["entries"]
            val created = clientChanges.first { it["action"].asText() == "clients.insert" && it["entity_id"].asText() == clientId.toString() }
            assertThat(created["actor_type"].asText()).isEqualTo("api_token")

            // A full export, holding what the API made.
            val parts = export(admin)
            assertThat(parts.keys).contains("manifest.json", "data/clients.json")
            assertThat(parts["data/clients.json"]!!.toString(Charsets.UTF_8)).contains("Kereru Books")
        } finally {
            freePlan.seats = seats
        }
    }

    /**
     * The plan lives in the `subscriptions` table and the billing package, and nowhere else. Code
     * that can reach neither can't give the paid plan a feature the free one lacks. The billing
     * package may read the plan; everything else may only ask the seat gate, before someone is
     * given sign-in access.
     */
    @Test
    fun `only the number of seats depends on the plan`() {
        val root = File("src/main/kotlin")
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .associateBy { it.relativeTo(root).invariantSeparatorsPath }
            .filterKeys { !it.startsWith("com/honestrobin/time/billing/") }
        assertThat(files).describedAs("main code to scan").isNotEmpty()
        assertThat(PLAN_ALLOWED.keys).describedAs("allowlisted files that no longer exist").allMatch { it in files }

        val patterns = PLAN_MARKERS.associateWith(::wholeWord)
        val offenders = files.mapNotNull { (path, file) ->
            val text = file.readText()
            val found = patterns.filterValues { it.containsMatchIn(text) }.keys - PLAN_ALLOWED[path]?.markers.orEmpty()
            if (found.isEmpty()) null else "$path: ${found.joinToString()}"
        }
        assertThat(offenders)
            .describedAs("Only the billing package may know the plan; other code may only ask the seat gate. If one of these is right, allowlist it with a reason")
            .isEmpty()
    }

    private fun wholeWord(marker: String) = Regex(
        (if (marker.first().isLetterOrDigit()) "\\b" else "") + Regex.escape(marker) + (if (marker.last().isLetterOrDigit()) "\\b" else ""),
    )

    /** Asks for an export, waits for the background job, and returns the zip's files by name. */
    private fun export(admin: TestClient): Map<String, ByteArray> {
        val id = admin.post("/api/v1/exports").expect(202).id()
        val deadline = System.currentTimeMillis() + 30_000
        while (true) {
            val e = admin.get("/api/v1/exports").expect(200).body.first { it["id"].asText() == id.toString() }
            if (e["status"].asText() == "ready") break
            assertThat(e["status"].asText()).withFailMessage { "Export failed: $e" }.isIn("queued", "running")
            check(System.currentTimeMillis() < deadline) { "Export did not finish: $e" }
            Thread.sleep(100)
        }
        val zip = admin.get("/api/v1/exports/$id/download").expect(200).bytes
        return ZipInputStream(zip.inputStream()).use { z -> generateSequence { z.nextEntry }.associate { it.name to z.readAllBytes() } }
    }
}

/** What knows about a subscription or a plan. Matched as whole words, in code and comments alike. */
private val PLAN_MARKERS = listOf(
    // The billing package, imported or written out.
    "com.honestrobin.time.billing",
    "BillingService", "SubscriptionSeatGate", "FreePlan", "PaddleSettings", "BillingPrices", "SubscriptionView",
    // Its tables: the subscription, which holds the plan, and Paddle's events.
    "SUBSCRIPTIONS", "SubscriptionsRecord", "subscriptions",
    "BILLING_EVENTS", "BillingEventsRecord", "billing_events",
    // Its settings, read without the package.
    "honestrobin.billing", "honestrobin.paddle",
    // A plan field on any record.
    ".plan", ".PLAN",
    // The seat gate: the one way the plan may change what someone can do.
    "SeatGate", "requireSeat", "requireSeatToJoin",
)

/** Main code outside the billing package that may name some of the markers, and why. */
private val PLAN_ALLOWED = mapOf(
    "com/honestrobin/time/accounts/Seats.kt" to PlanAllowance(
        setOf("SeatGate", "requireSeat", "requireSeatToJoin"),
        "defines the seat gate, which the billing package answers from the subscription",
    ),
    "com/honestrobin/time/accounts/People.kt" to PlanAllowance(
        setOf("SeatGate", "requireSeat"),
        "asks the seat gate before someone gets sign-in access: an invitation, or coming back after being deactivated",
    ),
    "com/honestrobin/time/auth/AuthService.kt" to PlanAllowance(
        setOf("SeatGate", "requireSeatToJoin"),
        "asks the seat gate when an invitation is accepted, before the person can sign in",
    ),
    "com/honestrobin/time/export/ExportFormat.kt" to PlanAllowance(
        setOf("SUBSCRIPTIONS", "BILLING_EVENTS"),
        "leaves the subscription tables out of the export on purpose; it reads nothing from them",
    ),
    "com/honestrobin/time/platform/edition/Edition.kt" to PlanAllowance(
        setOf("com.honestrobin.time.billing", "subscriptions"),
        "names the billing package, in code and a comment, among the packages that may differ by edition",
    ),
)

private class PlanAllowance(val markers: Set<String>, val reason: String)
