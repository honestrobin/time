// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.invoicing

import com.honestrobin.time.support.IntegrationTest
import com.honestrobin.time.support.TestClient
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executors

/** Spec §5.5 and acceptance tests AT-3.1, AT-3.2, AT-3.3 and AT-3.5. */
class InvoicingTest : IntegrationTest() {
    @Autowired
    lateinit var reminders: InvoiceReminderService

    private var restore: Duration? = null

    @AfterEach
    fun backToNow() {
        restore?.let(clock::restore)
        restore = null
    }

    private fun travel(instant: Instant) {
        val previous = clock.travelTo(instant)
        if (restore == null) restore = previous
    }

    private data class Setup(val admin: TestClient, val client: UUID, val project: UUID, val task: UUID)

    private fun setup(currency: String = "EUR", projectRate: Long = 10_000, vatId: String? = null): Setup {
        val admin = signup()
        val client = admin.post("/api/v1/clients", mapOf("name" to "Client ${UUID.randomUUID().toString().take(6)}", "currency" to currency, "vat_id" to vatId)).expect(201).id()
        admin.post("/api/v1/clients/$client/contacts", mapOf("name" to "Billing", "email" to "billing-$client@client.example", "is_invoice_recipient" to true)).expect(201)
        val task = createTask(admin)
        val project = createProject(admin, clientId = client, taskIds = listOf(task), extra = mapOf("bill_by" to "project", "hourly_rate" to projectRate)).id()
        return Setup(admin, client, project, task)
    }

    private fun entry(s: Setup, date: LocalDate, seconds: Int, billable: Boolean = true, project: UUID = s.project): UUID =
        s.admin.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to s.task, "spent_date" to date.toString(), "duration_seconds" to seconds, "billable" to billable)).expect(201).id()

    private fun invoiceIdOf(s: Setup, entry: UUID): String? = s.admin.get("/api/v1/time_entries/$entry").expect(200)["invoice_id"].takeIf { !it.isNull }?.asText()

    private fun freeForm(s: Setup, vararg lines: Map<String, Any?>, extra: Map<String, Any?> = emptyMap()) =
        s.admin.post("/api/v1/invoices", mapOf("client_id" to s.client, "lines" to lines.toList()) + extra).expect(201)

    @Test
    fun `an invoice from time takes exactly the uninvoiced billable time, and deleting it gives the time back (AT-3_1)`() {
        val s = setup()
        val inRange1 = entry(s, LocalDate.of(2026, 3, 2), 3600)
        val inRange2 = entry(s, LocalDate.of(2026, 3, 20), 5400)
        val notBillable = entry(s, LocalDate.of(2026, 3, 5), 7200, billable = false)
        val outOfRange = entry(s, LocalDate.of(2026, 4, 2), 3600)
        val other = setup()
        val otherClientEntry = entry(other, LocalDate.of(2026, 3, 3), 3600)
        s.admin.post("/api/v1/expense_categories", mapOf("name" to "Travel")).expect(201).id().let { cat ->
            s.admin.post("/api/v1/expenses", mapOf("project_id" to s.project, "category_id" to cat, "spent_date" to "2026-03-10", "amount" to 4_250, "billable" to true)).expect(201)
        }

        val preview = s.admin.get("/api/v1/invoices/uninvoiced", mapOf("client_id" to s.client, "from" to "2026-03-01", "to" to "2026-03-31")).expect(200)
        assertThat(preview["amount"].asLong()).isEqualTo(25_000)
        assertThat(preview["expense_amount"].asLong()).isEqualTo(4_250)

        val invoice = s.admin.post(
            "/api/v1/invoices",
            mapOf("client_id" to s.client, "from_time" to mapOf("from" to "2026-03-01", "to" to "2026-03-31", "grouping" to "project", "include_expenses" to true)),
        ).expect(201)
        val id = invoice.id().toString()
        assertThat(invoice["state"].asText()).isEqualTo("draft")
        assertThat(invoice["number"].isNull).isTrue()
        assertThat(invoice["lines"]).hasSize(2)
        assertThat(invoice["lines"][0]["quantity"].decimalValue()).isEqualByComparingTo("2.5")
        assertThat(invoice["lines"][0]["time_entry_count"].asInt()).isEqualTo(2)
        assertThat(invoice["subtotal"].asLong()).isEqualTo(25_000 + 4_250)
        assertThat(invoice["period_start"].asText()).isEqualTo("2026-03-02")

        assertThat(invoiceIdOf(s, inRange1)).isEqualTo(id)
        assertThat(invoiceIdOf(s, inRange2)).isEqualTo(id)
        assertThat(invoiceIdOf(s, notBillable)).isNull()
        assertThat(invoiceIdOf(s, outOfRange)).isNull()
        assertThat(invoiceIdOf(other, otherClientEntry)).isNull()
        // Invoiced time can't be edited or taken by another invoice.
        s.admin.patch("/api/v1/time_entries/$inRange1", mapOf("duration_seconds" to 60)).expectError(409, "invoiced")
        s.admin.post("/api/v1/invoices", mapOf("client_id" to s.client, "from_time" to mapOf("from" to "2026-03-01", "to" to "2026-03-31")))
            .expectError(422, "validation_failed")

        s.admin.delete("/api/v1/invoices/$id").expect(204)
        assertThat(invoiceIdOf(s, inRange1)).isNull()
        assertThat(invoiceIdOf(s, inRange2)).isNull()
        assertThat(s.admin.get("/api/v1/invoices/uninvoiced", mapOf("client_id" to s.client))["amount"].asLong()).isEqualTo(25_000 + 10_000)
    }

    @Test
    fun `removing a line frees its time, a sent invoice is voided not deleted, and voiding frees its time too`() {
        val s = setup()
        val e = entry(s, LocalDate.of(2026, 5, 4), 3600)
        val inv = s.admin.post("/api/v1/invoices", mapOf("client_id" to s.client, "from_time" to mapOf("grouping" to "detailed", "include_expenses" to false))).expect(201)
        val id = inv.id()
        val edited = s.admin.patch("/api/v1/invoices/$id", mapOf("lines" to listOf(mapOf("description" to "Retainer", "quantity" to 1, "unit_price" to 50_000)))).expect(200)
        assertThat(edited["lines"]).hasSize(1)
        assertThat(edited["total"].asLong()).isEqualTo(50_000)
        assertThat(invoiceIdOf(s, e)).isNull()

        val again = s.admin.post("/api/v1/invoices", mapOf("client_id" to s.client, "from_time" to mapOf("grouping" to "task"))).expect(201)
        s.admin.post("/api/v1/invoices/${again.id()}/mark_sent").expect(200)
        s.admin.delete("/api/v1/invoices/${again.id()}").expectError(409, "numbered")
        val voided = s.admin.post("/api/v1/invoices/${again.id()}/void").expect(200)
        assertThat(voided["state"].asText()).isEqualTo("void")
        assertThat(voided["number"].asText()).isNotBlank()
        assertThat(invoiceIdOf(s, e)).isNull()
        s.admin.patch("/api/v1/invoices/${again.id()}", mapOf("subject" to "x")).expectError(409, "void")
    }

    @Test
    fun `numbers are consecutive when invoices are sent at the same time, and a void number is never reused (AT-3_2)`() {
        val s = setup()
        s.admin.patch("/api/v1/invoice_settings", mapOf("number_prefix" to "{YYYY}-", "number_padding" to 3, "next_number" to 41)).expect(200)
        val ids = (1..8).map { freeForm(s, mapOf("description" to "Work $it", "quantity" to 1, "unit_price" to 1000)).id() }
        val pool = Executors.newFixedThreadPool(8)
        val results = ids.map { id -> pool.submit<String> { s.admin.post("/api/v1/invoices/$id/mark_sent").expect(200)["number"].asText() } }.map { it.get() }
        pool.shutdown()
        val year = s.admin.get("/api/v1/invoices/${ids[0]}")["issue_date"].asText().take(4)
        assertThat(results.sorted()).containsExactlyElementsOf((41..48).map { "$year-%03d".format(it) })

        s.admin.post("/api/v1/invoices/${ids[3]}/void").expect(200)
        val next = freeForm(s, mapOf("description" to "After", "quantity" to 1, "unit_price" to 1000)).id()
        assertThat(s.admin.post("/api/v1/invoices/$next/mark_sent").expect(200)["number"].asText()).isEqualTo("$year-049")
        assertThat(s.admin.get("/api/v1/invoice_settings")["next_number_preview"].asText()).isEqualTo("$year-050")
    }

    @Test
    fun `two named taxes and a discount compute like the worked example (AT-3_3)`() {
        val s = setup(currency = "AUD")
        val inv = freeForm(
            s,
            mapOf("description" to "Design", "quantity" to 1.5, "unit_price" to 100_000, "tax1_applies" to true, "tax2_applies" to true),
            mapOf("description" to "Hosting", "quantity" to 3, "unit_price" to 3_333, "tax1_applies" to true, "tax2_applies" to false),
            extra = mapOf("tax1_name" to "GST", "tax1_percent" to 10, "tax2_name" to "Levy", "tax2_percent" to 2.5, "discount_percent" to 5),
        )
        // Lines 150,000 + 9,999 = 159,999. Discount 5 % = 8,000 (7,999.95).
        // GST base 159,999 less 5 % (8,000) = 151,999 -> 15,200 (15,199.9). Levy base 150,000 less 7,500 = 142,500 -> 3,563 (3,562.5).
        assertThat(inv["subtotal"].asLong()).isEqualTo(159_999)
        assertThat(inv["discount"].asLong()).isEqualTo(8_000)
        assertThat(inv["tax1"].asLong()).isEqualTo(15_200)
        assertThat(inv["tax2"].asLong()).isEqualTo(3_563)
        assertThat(inv["total"].asLong()).isEqualTo(159_999 - 8_000 + 15_200 + 3_563)
    }

    @Test
    fun `VAT categories are taxed per category, reverse charge needs the buyer's tax ID and is printed on the invoice (AT-3_3)`() {
        val s = setup()
        val standard = freeForm(
            s,
            mapOf("description" to "Consulting", "quantity" to 10, "unit_price" to 12_345, "vat_category_code" to "S", "vat_percent" to 20),
            mapOf("description" to "Books", "quantity" to 1, "unit_price" to 4_999, "vat_category_code" to "S", "vat_percent" to 5),
            mapOf("description" to "Export", "quantity" to 1, "unit_price" to 10_000, "vat_category_code" to "Z"),
            extra = mapOf("vat_mode" to "standard"),
        )
        // 123,450 at 20 % = 24,690; 4,999 at 5 % = 249.95 -> 250; zero rated 10,000 -> 0.
        assertThat(standard["tax1"].asLong()).isEqualTo(24_690 + 250)
        assertThat(standard["total"].asLong()).isEqualTo(123_450 + 4_999 + 10_000 + 24_940)
        assertThat(standard["vat_breakdown"].map { it["category"].asText() + "/" + it["percent"].decimalValue().stripTrailingZeros().toPlainString() })
            .containsExactly("S/5", "S/20", "Z/0")

        s.admin.post("/api/v1/invoices", mapOf("client_id" to s.client, "vat_mode" to "reverse_charge")).expectError(422, "validation_failed")
        val eu = setup(vatId = "DE123456789")
        val rc = freeForm(eu, mapOf("description" to "Development", "quantity" to 8, "unit_price" to 9_500), extra = mapOf("vat_mode" to "reverse_charge"))
        assertThat(rc["lines"][0]["vat_category_code"].asText()).isEqualTo("AE")
        assertThat(rc["tax1"].asLong()).isZero()
        assertThat(rc["total"].asLong()).isEqualTo(76_000)
        eu.admin.post("/api/v1/invoices", mapOf("client_id" to eu.client, "vat_mode" to "exempt")).expectError(422, "validation_failed")

        eu.admin.post("/api/v1/invoices/${rc.id()}/mark_sent").expect(200)
        val text = pdfText(eu.admin.get("/api/v1/invoices/${rc.id()}/pdf").expect(200).bytes)
        assertThat(text).contains("Invoice", "Reverse charge", "DE123456789", "760.00")
    }

    @Test
    fun `sending emails the client with the PDF and a link, and the link opens the invoice for anyone who has it`() {
        val s = setup(currency = "JPY", projectRate = 15_000)
        entry(s, LocalDate.of(2026, 6, 1), 7200)
        val inv = s.admin.post("/api/v1/invoices", mapOf("client_id" to s.client, "from_time" to mapOf("grouping" to "project"))).expect(201)
        val defaults = s.admin.get("/api/v1/invoices/${inv.id()}/send").expect(200)
        assertThat(defaults["to"].map { it.asText() }).containsExactly("billing-${s.client}@client.example")
        // A draft's default texts already carry the number it will get.
        assertThat(defaults["subject"].asText()).startsWith("Invoice 1 from")

        val sent = s.admin.post("/api/v1/invoices/${inv.id()}/send", mapOf("bcc_me" to true)).expect(200)
        assertThat(sent["state"].asText()).isEqualTo("sent")
        assertThat(sent["number"].asText()).isEqualTo("1")
        val link = sent["public_url"].asText()
        val mail = this.mail.lastTo("billing-${s.client}@client.example")
        assertThat(mail.attachments.single().contentType).isEqualTo("application/pdf")
        assertThat(mail.text).contains(link)
        assertThat(mail.bcc).containsExactly(s.admin.email)
        assertThat(pdfText(mail.attachments.single().bytes)).contains("¥30,000")

        val token = link.substringAfterLast("/")
        val anonymous = client()
        val page = anonymous.get("/api/v1/public/invoices/$token").expect(200)
        assertThat(page["document"]["number"].asText()).isEqualTo("1")
        assertThat(page["document"]["amount_due"].asText()).isEqualTo("¥30,000")
        assertThat(anonymous.get("/api/v1/public/invoices/$token/pdf").expect(200).bytes.take(4).toByteArray().decodeToString()).isEqualTo("%PDF")
        assertThat(s.admin.get("/api/v1/invoices/${inv.id()}")["view_count"].asInt()).isEqualTo(1)
        anonymous.get("/api/v1/public/invoices/not-a-real-token-at-all-xx").expectError(404, "not_found")
    }

    @Test
    fun `payments settle the invoice, and a paid invoice can no longer change`() {
        val s = setup()
        val id = freeForm(s, mapOf("description" to "Work", "quantity" to 1, "unit_price" to 100_000)).id()
        s.admin.post("/api/v1/invoices/$id/payments", mapOf("amount" to 1000)).expectError(409, "not_payable")
        s.admin.post("/api/v1/invoices/$id/mark_sent").expect(200)
        val half = s.admin.post("/api/v1/invoices/$id/payments", mapOf("amount" to 40_000, "paid_date" to "2026-07-01")).expect(201)
        assertThat(half["state"].asText()).isEqualTo("partially_paid")
        assertThat(half["due"].asLong()).isEqualTo(60_000)
        s.admin.post("/api/v1/invoices/$id/payments", mapOf("amount" to 70_000)).expectError(422, "validation_failed")
        val paid = s.admin.post("/api/v1/invoices/$id/payments", mapOf("amount" to 60_000)).expect(201)
        assertThat(paid["state"].asText()).isEqualTo("paid")
        s.admin.patch("/api/v1/invoices/$id", mapOf("subject" to "Changed")).expectError(409, "paid")
        s.admin.post("/api/v1/invoices/$id/void").expectError(409, "has_payments")
        val reopened = s.admin.delete("/api/v1/invoices/$id/payments/${paid["payments"][1]["id"].asText()}").expect(200)
        assertThat(reopened["state"].asText()).isEqualTo("partially_paid")
        val summary = s.admin.get("/api/v1/invoices/summary").expect(200)
        assertThat(summary["totals"].single()["currency"].asText()).isEqualTo("EUR")
        assertThat(summary["totals"].single()["outstanding"].asLong()).isEqualTo(60_000)
    }

    @Test
    fun `reminders go out on the chosen days in the account's time zone, once each, and stop when paid (AT-3_5)`() {
        travel(Instant.parse("2026-08-10T10:00:00Z"))
        val s = setup()
        s.admin.patch("/api/v1/invoice_settings", mapOf("reminders_enabled" to true, "reminder_days" to listOf(-3, 0, 7))).expect(200)
        val id = freeForm(s, mapOf("description" to "Work", "quantity" to 1, "unit_price" to 100_000), extra = mapOf("payment_terms_days" to 5)).id()
        s.admin.post("/api/v1/invoices/$id/send", mapOf<String, Any>()).expect(200)
        val to = "billing-${s.client}@client.example"
        val account = s.admin.accountId!!
        fun runOn(day: String): Int {
            travel(Instant.parse("${day}T08:00:00Z"))
            return tx.run { com.honestrobin.time.platform.db.DbContext.forAccount(account) { reminders.remindAccount(account, setOf(-3, 0, 7)) } }
        }
        val before = mail.to(to).size
        assertThat(runOn("2026-08-11")).isZero()
        assertThat(runOn("2026-08-12")).isEqualTo(1) // three days before the due date (15 August)
        assertThat(mail.lastTo(to).subject).startsWith("Reminder: invoice 1")
        assertThat(runOn("2026-08-12")).isZero()
        assertThat(runOn("2026-08-15")).isEqualTo(1)
        assertThat(mail.to(to).size - before).isEqualTo(2)

        s.admin.post("/api/v1/invoices/$id/payments", mapOf("amount" to 100_000)).expect(201)
        assertThat(runOn("2026-08-22")).isZero()
    }

    @Test
    fun `only people who may manage invoices see them`() {
        val s = setup()
        val member = invite(s.admin)
        member.get("/api/v1/invoices").expectError(403, "forbidden")
        val manager = invite(s.admin, role = "manager", extra = mapOf("can_manage_invoices" to false))
        manager.get("/api/v1/invoices").expectError(403, "forbidden")
        val invoicer = invite(s.admin, role = "manager", extra = mapOf("can_manage_invoices" to true))
        invoicer.get("/api/v1/invoices").expect(200)
        invoicer.patch("/api/v1/invoice_settings", mapOf("payment_terms_days" to 14)).expectError(403, "forbidden")
    }

    private fun pdfText(bytes: ByteArray): String = Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) }
}
