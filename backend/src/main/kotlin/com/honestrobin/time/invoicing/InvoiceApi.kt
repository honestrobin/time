// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.invoicing

import tools.jackson.databind.JsonNode
import com.honestrobin.time.db.Tables.ACCOUNTS
import com.honestrobin.time.db.Tables.INVOICES
import com.honestrobin.time.platform.db.DbContext
import com.honestrobin.time.platform.db.Tx
import com.honestrobin.time.platform.security.Current
import com.honestrobin.time.platform.security.Member
import com.honestrobin.time.platform.web.NotFoundException
import com.honestrobin.time.platform.web.Patch
import com.honestrobin.time.platform.web.Patches
import com.honestrobin.time.platform.web.ValidationException
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.jooq.DSLContext
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class InvoiceSettingsView(
    val paymentTermsDays: Int,
    val notes: String?,
    val footer: String?,
    val paymentInstructions: String?,
    val defaultTax1Name: String?,
    val defaultTax1Percent: BigDecimal?,
    val defaultTax2Name: String?,
    val defaultTax2Percent: BigDecimal?,
    val remindersEnabled: Boolean,
    val reminderDays: List<Int>,
    val bccSender: Boolean,
    val numberPrefix: String,
    val nextNumber: Long,
    val numberPadding: Int,
    val numberPerYear: Boolean,
    /** What the next invoice number will look like. */
    val nextNumberPreview: String,
    /** Factur-X hybrid PDFs: true, false, or null to follow the default (on in EU countries). */
    val einvoiceHybridPdf: Boolean?,
    /** Whether invoice PDFs go out as hybrid PDFs now. */
    val einvoiceHybridPdfActive: Boolean,
    val invoiceContactName: String?,
    val invoiceContactEmail: String?,
    val invoiceContactPhone: String?,
)

data class InvoiceSettingsInput(
    val paymentTermsDays: Int? = null,
    val notes: String? = null,
    val footer: String? = null,
    val paymentInstructions: String? = null,
    val defaultTax1Name: String? = null,
    val defaultTax1Percent: BigDecimal? = null,
    val defaultTax2Name: String? = null,
    val defaultTax2Percent: BigDecimal? = null,
    val remindersEnabled: Boolean? = null,
    val reminderDays: List<Int>? = null,
    val bccSender: Boolean? = null,
    val numberPrefix: String? = null,
    val nextNumber: Long? = null,
    val numberPadding: Int? = null,
    val numberPerYear: Boolean? = null,
    val einvoiceHybridPdf: Boolean? = null,
    val invoiceContactName: String? = null,
    val invoiceContactEmail: String? = null,
    val invoiceContactPhone: String? = null,
)

/** Account-wide invoice defaults and numbering (admins). */
@Service
class InvoiceSettingsService(
    private val dsl: DSLContext,
    private val numbering: InvoiceNumbering,
    private val einvoices: com.honestrobin.time.einvoice.EInvoiceService,
    private val clock: java.time.Clock,
    private val recentAuth: com.honestrobin.time.platform.security.RecentAuth,
) {
    @Transactional
    fun get(m: Member): InvoiceSettingsView {
        if (!m.isAdmin && !m.canManageInvoices) throw com.honestrobin.time.platform.web.ForbiddenException()
        return view(m)
    }

    @Transactional
    fun update(m: Member, patch: Patch<InvoiceSettingsInput>): InvoiceSettingsView {
        m.requireWritable()
        m.requireAdmin()
        val a = dsl.selectFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(m.accountId)).fetchOne()!!
        val seq = numbering.defaultSequence(m.accountId, lock = true)
        val i = patch.value
        val errors = mutableMapOf<String, String>()
        patch.field("payment_terms_days", { paymentTermsDays }) { if (it == null || it !in 0..365) errors["payment_terms_days"] = "Use 0 to 365 days" else a.invoicePaymentTermsDays = it }
        patch.field("notes", { notes }) { a.invoiceNotes = it?.ifBlank { null } }
        patch.field("footer", { footer }) { a.invoiceFooter = it?.ifBlank { null } }
        patch.field("payment_instructions", { paymentInstructions }) {
            // They tell clients where to pay: changing them is how payments get redirected. A browser
            // session, not an API token: a token can be phished through device sign-in.
            val v = it?.ifBlank { null }
            if (v != a.paymentInstructions) recentAuth.require()
            a.paymentInstructions = v
        }
        patch.field("default_tax1_name", { defaultTax1Name }) { a.defaultTax1Name = it?.ifBlank { null } }
        patch.field("default_tax1_percent", { defaultTax1Percent }) { a.defaultTax1Percent = it }
        patch.field("default_tax2_name", { defaultTax2Name }) { a.defaultTax2Name = it?.ifBlank { null } }
        patch.field("default_tax2_percent", { defaultTax2Percent }) { a.defaultTax2Percent = it }
        patch.field("reminders_enabled", { remindersEnabled }) { a.invoiceRemindersEnabled = it ?: false }
        patch.field("reminder_days", { reminderDays }) { days ->
            val clean = days.orEmpty().distinct().sorted()
            if (clean.any { it !in -30..90 } || clean.size > 6) errors["reminder_days"] = "Choose up to six days, from 30 days before to 90 days after the due date"
            else a.invoiceReminderDays = clean.toTypedArray()
        }
        patch.field("bcc_sender", { bccSender }) { a.invoiceBccSender = it ?: false }
        patch.field("number_prefix", { numberPrefix }) { if ((it ?: "").length > 20) errors["number_prefix"] = "Use at most 20 characters" else seq.prefix = it ?: "" }
        patch.field("next_number", { nextNumber }) { if (it == null || it < 1) errors["next_number"] = "Use a number from 1" else seq.nextNumber = it }
        patch.field("number_padding", { numberPadding }) { if (it == null || it !in 0..12) errors["number_padding"] = "Use 0 to 12 digits" else seq.padding = it.toShort() }
        patch.field("number_per_year", { numberPerYear }) { seq.perYear = it ?: false }
        patch.field("einvoice_hybrid_pdf", { einvoiceHybridPdf }) { a.einvoiceHybridPdf = it }
        patch.field("invoice_contact_name", { invoiceContactName }) { a.invoiceContactName = it?.trim()?.ifBlank { null } }
        patch.field("invoice_contact_email", { invoiceContactEmail }) {
            val v = it?.trim()?.ifBlank { null }
            if (v != null && !v.matches(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))) errors["invoice_contact_email"] = "Enter a valid email address" else a.invoiceContactEmail = v
        }
        patch.field("invoice_contact_phone", { invoiceContactPhone }) { a.invoiceContactPhone = it?.trim()?.ifBlank { null } }
        listOf("default_tax1_percent" to a.defaultTax1Percent, "default_tax2_percent" to a.defaultTax2Percent).forEach { (f, v) ->
            if (v != null && (v < BigDecimal.ZERO || v > BigDecimal(100))) errors[f] = "Enter a percentage from 0 to 100"
        }
        if (a.defaultTax1Percent != null && a.defaultTax1Name == null) errors["default_tax1_name"] = "Name the tax, e.g. VAT, GST or Sales tax"
        if (a.defaultTax2Percent != null && a.defaultTax2Name == null) errors["default_tax2_name"] = "Name the second tax"
        if (errors.isNotEmpty()) throw ValidationException(errors)
        a.store()
        seq.store()
        return view(m)
    }

    private fun view(m: Member): InvoiceSettingsView {
        val a = dsl.selectFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(m.accountId)).fetchOne()!!
        val seq = numbering.defaultSequence(m.accountId)
        return InvoiceSettingsView(
            a.invoicePaymentTermsDays, a.invoiceNotes, a.invoiceFooter, a.paymentInstructions ?: InvoiceService.defaultPaymentInstructions(a.iban, a.bic),
            a.defaultTax1Name, a.defaultTax1Percent, a.defaultTax2Name, a.defaultTax2Percent,
            a.invoiceRemindersEnabled, a.invoiceReminderDays.map { it.toInt() }, a.invoiceBccSender,
            seq.prefix, seq.nextNumber, seq.padding.toInt(), seq.perYear, numbering.preview(seq, LocalDate.now(clock).year),
            a.einvoiceHybridPdf, einvoices.hybridEnabled(a), a.invoiceContactName, a.invoiceContactEmail, a.invoiceContactPhone,
        )
    }
}

@RestController
@RequestMapping("/api/v1/invoices")
@Tag(name = "invoices", description = "Invoices, payments and sending")
class InvoiceController(
    private val invoices: InvoiceService,
    private val sender: InvoiceSender,
    private val pdf: InvoicePdf,
    private val einvoices: com.honestrobin.time.einvoice.EInvoiceService,
    private val patches: Patches,
    private val tx: Tx,
) {
    @GetMapping
    fun list(
        @RequestParam(required = false) state: String?,
        @RequestParam(name = "client_id", required = false) clientId: UUID?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ) = invoices.list(Current.member(), state, clientId, cursor, limit)

    @GetMapping("/summary")
    fun summary() = invoices.summary(Current.member())

    @GetMapping("/uninvoiced")
    fun uninvoiced(
        @RequestParam(name = "client_id") clientId: UUID,
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
    ) = invoices.uninvoiced(Current.member(), clientId, from, to)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody body: InvoiceInput) = invoices.create(Current.member(), body)

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID) = invoices.get(Current.member(), id)

    @PatchMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @io.swagger.v3.oas.annotations.parameters.RequestBody(content = [Content(schema = Schema(implementation = InvoiceInput::class))]) @RequestBody body: JsonNode,
    ) = invoices.update(Current.member(), id, patches.parse(body))

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) = invoices.delete(Current.member(), id)

    @GetMapping("/{id}/send")
    fun sendDefaults(@PathVariable id: UUID) = sender.defaults(Current.member(), id)

    @PostMapping("/{id}/send")
    fun send(@PathVariable id: UUID, @RequestBody body: SendInvoiceInput) = sender.send(Current.member(), id, body)

    @PostMapping("/{id}/mark_sent")
    fun markSent(@PathVariable id: UUID) = invoices.markSent(Current.member(), id)

    @PostMapping("/{id}/void")
    fun void(@PathVariable id: UUID) = invoices.void(Current.member(), id)

    @PostMapping("/{id}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    fun addPayment(@PathVariable id: UUID, @RequestBody body: PaymentInput) = invoices.addPayment(Current.member(), id, body)

    @DeleteMapping("/{id}/payments/{paymentId}")
    fun deletePayment(@PathVariable id: UUID, @PathVariable paymentId: UUID) = invoices.deletePayment(Current.member(), id, paymentId)

    @GetMapping("/{id}/pdf", produces = [MediaType.APPLICATION_PDF_VALUE])
    fun pdf(@PathVariable id: UUID): ResponseEntity<ByteArray> {
        val m = Current.member()
        invoices.requireAccess(m)
        val (bytes, name) = tx.run { invoices.load(id).let { einvoices.invoicePdf(it) to pdf.filename(it) } }
        return pdfResponse(bytes, name)
    }

    /** What each e-invoice format still needs from this invoice, its client and the account. */
    @GetMapping("/{id}/einvoice/readiness")
    fun einvoiceReadiness(@PathVariable id: UUID): List<com.honestrobin.time.einvoice.EInvoiceReadiness> =
        com.honestrobin.time.einvoice.EInvoiceFormat.entries.map { einvoices.readiness(Current.member(), id, it) }

    /** Downloads the invoice as an e-invoice: format facturx (PDF), xrechnung or peppol (XML). */
    @GetMapping("/{id}/einvoice")
    fun einvoice(@PathVariable id: UUID, @org.springframework.web.bind.annotation.RequestParam format: String): ResponseEntity<ByteArray> {
        val f = einvoices.generate(Current.member(), id, com.honestrobin.time.einvoice.EInvoiceFormat.of(format))
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.contentType))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(f.filename).build().toString())
            .body(f.bytes)
    }
}

@RestController
@RequestMapping("/api/v1/invoice_settings")
@Tag(name = "invoices", description = "Invoices, payments and sending")
class InvoiceSettingsController(private val service: InvoiceSettingsService, private val patches: Patches) {
    @GetMapping
    fun get() = service.get(Current.member())

    @PatchMapping
    fun update(
        @io.swagger.v3.oas.annotations.parameters.RequestBody(content = [Content(schema = Schema(implementation = InvoiceSettingsInput::class))]) @RequestBody body: JsonNode,
    ) = service.update(Current.member(), patches.parse(body))
}

data class PublicInvoiceView(val document: InvoiceDocument, val state: String, val pdfUrl: String, val canPayOnline: Boolean)

/** The page a client opens from the invoice email; anyone with the link can see that one invoice. */
@RestController
@RequestMapping("/api/v1/public/invoices")
@Tag(name = "public", description = "Pages for people outside the account")
class PublicInvoiceController(
    private val dsl: DSLContext,
    private val tx: Tx,
    private val pdf: InvoicePdf,
    private val einvoices: com.honestrobin.time.einvoice.EInvoiceService,
    private val payments: com.honestrobin.time.payments.OnlinePaymentService,
) {
    private fun find(token: String) = tx.system {
        if (token.length < 20) null else dsl.selectFrom(INVOICES).where(INVOICES.PUBLIC_TOKEN.eq(token)).and(INVOICES.STATE.ne("draft")).fetchOne()
    } ?: throw NotFoundException("Invoice")

    @GetMapping("/{token}")
    fun get(@PathVariable token: String): PublicInvoiceView {
        val r = find(token)
        return DbContext.anonymouslyForAccount(r.accountId) {
            tx.run {
                dsl.update(INVOICES).set(INVOICES.VIEW_COUNT, INVOICES.VIEW_COUNT.plus(1)).set(INVOICES.LAST_VIEWED_AT, java.time.Instant.now())
                    .where(INVOICES.ID.eq(r.id)).execute()
                val payable = r.state in setOf("sent", "open", "partially_paid") && r.dueMinor > 0 && !r.isReadOnly
                PublicInvoiceView(pdf.document(r), r.state, "/api/v1/public/invoices/$token/pdf", canPayOnline = payable && payments.canPayOnline(r.accountId))
            }
        }
    }

    @GetMapping("/{token}/pdf", produces = [MediaType.APPLICATION_PDF_VALUE])
    fun pdf(@PathVariable token: String): ResponseEntity<ByteArray> {
        val r = find(token)
        val (bytes, name) = DbContext.forAccount(r.accountId) { tx.run { einvoices.invoicePdf(r) to pdf.filename(r) } }
        return pdfResponse(bytes, name)
    }
}

private fun pdfResponse(bytes: ByteArray, filename: String): ResponseEntity<ByteArray> =
    ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(filename).build().toString())
        .body(bytes)
