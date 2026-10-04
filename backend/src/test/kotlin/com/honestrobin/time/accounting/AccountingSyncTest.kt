// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.accounting

import com.honestrobin.time.db.Tables.ACCOUNTING_SYNC_ITEMS
import com.honestrobin.time.db.Tables.AUDIT_LOG
import com.honestrobin.time.db.Tables.EXTERNAL_LINKS
import com.honestrobin.time.db.Tables.INTEGRATIONS
import com.honestrobin.time.support.IntegrationTest
import com.honestrobin.time.support.MockAccounting
import com.honestrobin.time.support.TestClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/** Spec §8 and AT-5.3: pushing invoices and payments to QuickBooks Online and Xero, against stand-ins. */
class AccountingSyncTest : IntegrationTest() {
    @Autowired lateinit var accounting: AccountingService

    @Autowired lateinit var deletions: com.honestrobin.time.export.AccountDeletionService

    private fun connect(admin: TestClient, kind: String) {
        val url = admin.post("/api/v1/accounting/$kind/connect").expect(200)["url"].asText()
        assertThat(url).contains("client_id=${MockAccounting.CLIENT_ID}")
        val state = java.net.URLDecoder.decode(Regex("state=([^&]+)").find(url)!!.groupValues[1], Charsets.UTF_8)
        val bad = admin.get("/api/v1/public/accounting/$kind/callback", mapOf("code" to "good-code", "state" to "${admin.accountId}.wrong", "realmId" to MockAccounting.REALM))
        assertThat(bad.headers["Location"]!!.single()).endsWith("accounting=error")
        // The provider sends back the browser that started, signed in as the admin.
        val ok = admin.get("/api/v1/public/accounting/$kind/callback", mapOf("code" to "good-code", "state" to state, "realmId" to MockAccounting.REALM))
        assertThat(ok.headers["Location"]!!.single()).endsWith("accounting=connected&provider=$kind")
    }

    private fun startConnect(admin: TestClient, kind: String): String {
        val url = admin.post("/api/v1/accounting/$kind/connect").expect(200)["url"].asText()
        return java.net.URLDecoder.decode(Regex("state=([^&]+)").find(url)!!.groupValues[1], Charsets.UTF_8)
    }

    private fun finish(c: TestClient, state: String) =
        c.get("/api/v1/public/accounting/qbo/callback", mapOf("code" to "good-code", "state" to state, "realmId" to MockAccounting.REALM)).headers["Location"]!!.single()

    private fun connected(admin: TestClient) = admin.get("/api/v1/accounting").expect(200).body.first { it["kind"].asText() == "qbo" }["connected"].asBoolean()

    @Test
    fun `only the browser that started connecting can finish it, and only for half an hour`() {
        // Security review, 4 October 2026: someone could start connecting in their own workspace,
        // then get another business's admin to authorise it, attaching those books to theirs.
        val starter = signup(accountName = "Starter Ltd")
        val state = startConnect(starter, "qbo")
        val someoneElse = signup(accountName = "Other Books Ltd")
        assertThat(finish(someoneElse, state)).endsWith("accounting=error")
        assertThat(finish(client(), state)).endsWith("accounting=error")
        // The same person signed in elsewhere is another browser.
        val elsewhere = client()
        elsewhere.post("/api/v1/auth/login", mapOf("email" to starter.email, "password" to "correct horse battery")).expect(200)
        elsewhere.accountId = starter.accountId
        assertThat(finish(elsewhere, state)).endsWith("accounting=error")
        assertThat(connected(starter)).isFalse()

        // What's kept is a hash, so the audit log and exports hold nothing usable.
        val token = state.substringAfter('.')
        val settings = tx.system { dsl.select(INTEGRATIONS.SETTINGS).from(INTEGRATIONS).where(INTEGRATIONS.ACCOUNT_ID.eq(starter.accountId)).fetchOne()!!.value1().data() }
        assertThat(settings).doesNotContain(token)
        val audit = tx.system { dsl.select(AUDIT_LOG.DIFF).from(AUDIT_LOG).where(AUDIT_LOG.ACCOUNT_ID.eq(starter.accountId)).fetch().map { it.value1().data() } }
        assertThat(audit).noneMatch { it.contains(token) }

        // After half an hour the state no longer works, even in the right browser.
        val expired = mapper.readTree(settings).also { (it["oauth"] as tools.jackson.databind.node.ObjectNode).put("expires_at", java.time.Instant.now().minusSeconds(1).toString()) }
        tx.system { dsl.update(INTEGRATIONS).set(INTEGRATIONS.SETTINGS, org.jooq.JSONB.valueOf(mapper.writeValueAsString(expired))).where(INTEGRATIONS.ACCOUNT_ID.eq(starter.accountId)).execute() }
        assertThat(finish(starter, state)).endsWith("accounting=error")

        // A fresh start in the right browser works, once.
        val again = startConnect(starter, "qbo")
        assertThat(finish(starter, again)).endsWith("accounting=connected&provider=qbo")
        assertThat(connected(starter)).isTrue()
        assertThat(finish(starter, again)).endsWith("accounting=error")
    }

    private fun invoice(admin: TestClient, client: UUID, percent: Int = 19): UUID {
        val id = admin.post(
            "/api/v1/invoices",
            mapOf("client_id" to client, "vat_mode" to "standard", "discount_percent" to 5,
                "lines" to listOf(mapOf("description" to "Design", "quantity" to 8, "unit_price" to 12_000, "vat_category_code" to "S", "vat_percent" to percent))),
        ).expect(201).id()
        admin.post("/api/v1/invoices/$id/mark_sent").expect(200)
        return id
    }

    private fun items(invoice: UUID, admin: TestClient) = admin.get("/api/v1/invoices/$invoice/accounting").expect(200).body

    @Test
    fun `QuickBooks gets each invoice and payment once, across retries (AT-5_3)`() {
        val admin = signup()
        val client = createClient(admin, "Kiwi Outdoor Ltd", "NZD")
        connect(admin, "qbo")
        val status = admin.get("/api/v1/accounting").expect(200).body.first { it["kind"].asText() == "qbo" }
        assertThat(status["connected"].asBoolean()).isTrue()
        assertThat(status["organisation"].asText()).isEqualTo("Fjord & Pine (QuickBooks)")

        // Without a tax code for 19%, the push fails at once and says what to choose.
        val first = invoice(admin, client)
        accounting.processAccount(admin.accountId!!)
        val failed = items(first, admin).single()
        assertThat(failed["status"].asText()).isEqualTo("failed")
        assertThat(failed["last_error"].asText()).contains("tax code for VAT S 19%")

        val options = admin.get("/api/v1/accounting/qbo/options").expect(200)
        assertThat(options["our_taxes"].values().map { it["id"].asText() }).contains("S:19")
        admin.patch("/api/v1/accounting/qbo/mapping", mapOf("tax_codes" to mapOf("S:19" to "TAX19"), "item_id" to "1", "payment_account" to "35")).expect(200)
        accounting.processAccount(admin.accountId!!)
        assertThat(items(first, admin).single()["status"].asText()).isEqualTo("done")
        val pushed = MockAccounting.created["qbo:Invoice"]!!.last()["body"]
        assertThat(pushed["DocNumber"].asText()).isNotBlank()
        assertThat(pushed["CurrencyRef"]["value"].asText()).isEqualTo("NZD")
        assertThat(pushed["Line"][0]["SalesItemLineDetail"]["TaxCodeRef"]["value"].asText()).isEqualTo("TAX19")
        assertThat(pushed["Line"][1]["DetailType"].asText()).isEqualTo("DiscountLineDetail")

        // A payment follows its invoice.
        admin.post("/api/v1/invoices/$first/payments", mapOf("amount" to 10_000, "paid_on" to "2026-10-02")).expect(201)
        accounting.processAccount(admin.accountId!!)
        assertThat(items(first, admin).values().map { it["entity_type"].asText() to it["status"].asText() }).containsExactly("invoice" to "done", "payment" to "done")
        val payment = MockAccounting.created["qbo:Payment"]!!.last()["body"]
        assertThat(payment["TotalAmt"].decimalValue()).isEqualByComparingTo("100.00")

        // Retries never duplicate: forget the links (as if the answer got lost) and push again.
        val invoices = MockAccounting.createdCount("qbo:Invoice")
        val payments = MockAccounting.createdCount("qbo:Payment")
        tx.system { dsl.deleteFrom(EXTERNAL_LINKS).where(EXTERNAL_LINKS.ACCOUNT_ID.eq(admin.accountId)).and(EXTERNAL_LINKS.ENTITY_TYPE.`in`("invoice", "payment")).execute() }
        tx.system { dsl.update(ACCOUNTING_SYNC_ITEMS).set(ACCOUNTING_SYNC_ITEMS.STATUS, "pending").where(ACCOUNTING_SYNC_ITEMS.ACCOUNT_ID.eq(admin.accountId)).execute() }
        accounting.processAccount(admin.accountId!!)
        assertThat(MockAccounting.createdCount("qbo:Invoice")).isEqualTo(invoices)
        assertThat(MockAccounting.createdCount("qbo:Payment")).isEqualTo(payments)
        // And the customer was found or created once.
        assertThat(MockAccounting.calls.count { it.path.endsWith("/customer") && it.method == "POST" && it.body?.get("DisplayName")?.asText() == "Kiwi Outdoor Ltd" }).isEqualTo(1)
    }

    @Test
    fun `Xero gets invoices with the chosen account and tax rate, and tokens refresh`() {
        val admin = signup()
        val client = createClient(admin, "Tui Studio", "GBP")
        MockAccounting.expiresIn = 0 // every call needs a refresh
        try {
            connect(admin, "xero")
            val options = admin.get("/api/v1/accounting/xero/options").expect(200)
            assertThat(options["sales_accounts"].values().map { it["id"].asText() }).contains("200")
            admin.patch("/api/v1/accounting/xero/mapping", mapOf("tax_codes" to mapOf("S:20" to "OUTPUT2"), "sales_account" to "200", "payment_account" to "acc-bank")).expect(200)
            val inv = invoice(admin, client, percent = 20)
            admin.post("/api/v1/invoices/$inv/payments", mapOf("amount" to 5_000, "paid_on" to "2026-10-02")).expect(201)
            accounting.processAccount(admin.accountId!!)
            assertThat(items(inv, admin).values().map { it["status"].asText() }).containsExactly("done", "done")
            val call = MockAccounting.calls.last { it.path == "/api.xro/2.0/Invoices" }
            assertThat(call.headers["Xero-tenant-id"]).isEqualTo(MockAccounting.XERO_TENANT)
            assertThat(call.headers["Idempotency-Key"]).isNotBlank()
            val line = call.body!!["Invoices"][0]["LineItems"][0]
            assertThat(line["AccountCode"].asText()).isEqualTo("200")
            assertThat(line["TaxType"].asText()).isEqualTo("OUTPUT2")
            assertThat(line["DiscountRate"].decimalValue()).isEqualByComparingTo("5")
            // Tokens that expire at once are refreshed before use.
            assertThat(MockAccounting.calls.filter { it.path.endsWith("/connect/token") }.map { it.raw }).anyMatch { it.contains("grant_type=refresh_token") }
            assertThat(MockAccounting.calls.last { it.path == "/api.xro/2.0/Payments" }.body!!["Payments"][0]["Account"]["AccountID"].asText()).isEqualTo("acc-bank")
        } finally {
            MockAccounting.expiresIn = 3600
        }
    }

    @Test
    fun `disconnecting ends Honest Robin's access over there, and in Xero only to this workspace's organisation`() {
        // Security review, 4 October 2026: disconnecting only forgot the tokens, and Xero used the
        // first of all the person's organisations, not the one just authorised.
        val admin = signup()
        connect(admin, "qbo")
        val before = MockAccounting.removed.size
        val qbo = admin.delete("/api/v1/accounting/qbo").expect(200)
        assertThat(qbo["revoked"].asBoolean()).isTrue()
        assertThat(qbo["note"].isNull || qbo["note"].isMissingNode).isTrue()
        assertThat(MockAccounting.removed.drop(before)).singleElement().matches { it.startsWith("qbo:rt_") }
        assertThat(connected(admin)).isFalse()

        connect(admin, "xero")
        val xero = admin.get("/api/v1/accounting").expect(200).body.first { it["kind"].asText() == "xero" }
        assertThat(xero["organisation"].asText()).isEqualTo("Fjord & Pine (Xero)")
        assertThat(admin.delete("/api/v1/accounting/xero").expect(200)["revoked"].asBoolean()).isTrue()
        assertThat(MockAccounting.removed).contains("xero:conn-1").doesNotContain("xero:conn-0")
    }

    @Test
    fun `deleting an account ends Honest Robin's access to its books`() {
        val admin = signup()
        connect(admin, "qbo")
        val before = MockAccounting.removed.size
        deletions.purge(admin.accountId!!)
        assertThat(MockAccounting.removed.drop(before)).singleElement().matches { it.startsWith("qbo:rt_") }
    }

    @Test
    fun `nothing is queued without a connection, and a manual push needs one`() {
        val admin = signup()
        val inv = invoice(admin, createClient(admin))
        assertThat(items(inv, admin)).isEmpty()
        admin.post("/api/v1/invoices/$inv/accounting/push").expectError(409, "not_connected")
        admin.post("/api/v1/accounting/sage/connect").expectError(404, "not_found")
    }
}
