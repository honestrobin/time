// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.accounting

import com.honestrobin.time.db.Tables.ACCOUNTING_SYNC_ITEMS
import com.honestrobin.time.db.Tables.EXTERNAL_LINKS
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

    private fun connect(admin: TestClient, kind: String) {
        val url = admin.post("/api/v1/accounting/$kind/connect").expect(200)["url"].asText()
        assertThat(url).contains("client_id=${MockAccounting.CLIENT_ID}")
        val state = java.net.URLDecoder.decode(Regex("state=([^&]+)").find(url)!!.groupValues[1], Charsets.UTF_8)
        val bad = client().get("/api/v1/public/accounting/$kind/callback", mapOf("code" to "good-code", "state" to "${admin.accountId}.wrong", "realmId" to MockAccounting.REALM))
        assertThat(bad.headers["Location"]!!.single()).endsWith("accounting=error")
        val ok = client().get("/api/v1/public/accounting/$kind/callback", mapOf("code" to "good-code", "state" to state, "realmId" to MockAccounting.REALM))
        assertThat(ok.headers["Location"]!!.single()).endsWith("accounting=connected&provider=$kind")
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
    fun `nothing is queued without a connection, and a manual push needs one`() {
        val admin = signup()
        val inv = invoice(admin, createClient(admin))
        assertThat(items(inv, admin)).isEmpty()
        admin.post("/api/v1/invoices/$inv/accounting/push").expectError(409, "not_connected")
        admin.post("/api/v1/accounting/sage/connect").expectError(404, "not_found")
    }
}
