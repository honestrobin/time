// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.invoicing

import com.honestrobin.time.platform.forMessage
import com.honestrobin.time.db.Tables.ACCOUNTS
import com.honestrobin.time.db.Tables.CLIENTS
import com.honestrobin.time.db.Tables.INVOICE_LINES
import com.honestrobin.time.db.tables.records.InvoicesRecord
import com.honestrobin.time.platform.Money
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import org.jooq.DSLContext
import org.springframework.context.MessageSource
import org.springframework.stereotype.Component
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

data class Party(val name: String, val lines: List<String>, val taxId: String?, val companyRegNo: String?, val electronicAddress: String?)

data class DocumentLine(val description: String, val quantity: String, val unitPrice: String, val amount: String, val vat: String?)

data class DocumentRow(val label: String, val amount: String, val strong: Boolean = false)

/** An invoice as it is printed and shown on the public page: every value already formatted. */
data class InvoiceDocument(
    val title: String,
    val number: String?,
    val state: String,
    val issueDate: String,
    val dueDate: String?,
    val period: String?,
    val purchaseOrder: String?,
    val buyerReference: String?,
    val subject: String?,
    val seller: Party,
    val buyer: Party,
    val lines: List<DocumentLine>,
    val showVatColumn: Boolean,
    val rows: List<DocumentRow>,
    val vatBreakdown: List<List<String>>,
    val amountDue: String,
    val notes: String?,
    val paymentInstructions: String?,
    val legalNote: String?,
    val footer: String?,
    val currency: String,
    val dueMinor: Long,
)

/**
 * Builds the printable form of an invoice and renders it to PDF. Labels come from the message
 * bundle in the account's locale; amounts are formatted for that locale in the invoice currency.
 * Noto Sans is embedded so names and symbols outside Latin-1 print correctly.
 */
@Component
class InvoicePdf(
    private val dsl: DSLContext,
    private val engine: SpringTemplateEngine,
    private val messages: MessageSource,
    private val invoices: InvoiceService,
) {
    fun document(r: InvoicesRecord): InvoiceDocument {
        val account = dsl.selectFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(r.accountId)).fetchOne()!!
        val client = dsl.selectFrom(CLIENTS).where(CLIENTS.ID.eq(r.clientId)).fetchOne()!!
        val locale = Locale.forLanguageTag(account.locale)
        fun msg(key: String, vararg args: Any?) = messages.getMessage("invoice.$key", args.forMessage(), locale)
        val money = money(r.currency, locale)
        val dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
        val lines = dsl.selectFrom(INVOICE_LINES).where(INVOICE_LINES.INVOICE_ID.eq(r.id)).orderBy(INVOICE_LINES.POSITION).fetch()
        val totals = invoices.totals(r, lines)
        val vat = r.vatMode != null

        val rows = mutableListOf(DocumentRow(msg("subtotal"), money(r.subtotalMinor)))
        if (r.discountMinor > 0) rows += DocumentRow(msg("discount", percent(r.discountPercent, locale)), "−" + money(r.discountMinor))
        if (vat) {
            rows += DocumentRow(msg("vat"), money(r.tax1Minor))
        } else {
            if (r.tax1Percent != null) rows += DocumentRow("${r.tax1Name ?: msg("tax")} (${percent(r.tax1Percent, locale)})", money(r.tax1Minor))
            if (r.tax2Percent != null) rows += DocumentRow("${r.tax2Name ?: msg("tax")} (${percent(r.tax2Percent, locale)})", money(r.tax2Minor))
        }
        rows += DocumentRow(msg("total"), money(r.totalMinor), strong = true)
        if (r.paidMinor > 0) rows += DocumentRow(msg("paid"), "−" + money(r.paidMinor))

        return InvoiceDocument(
            title = msg("title"),
            number = r.number,
            state = r.state,
            issueDate = r.issueDate.format(dates),
            dueDate = r.dueDate?.format(dates),
            period = if (r.periodStart != null && r.periodEnd != null) "${r.periodStart.format(dates)} – ${r.periodEnd.format(dates)}" else null,
            purchaseOrder = r.purchaseOrder?.ifBlank { null },
            buyerReference = r.buyerReference?.ifBlank { null },
            subject = r.subject?.ifBlank { null },
            seller = Party(
                account.legalName?.ifBlank { null } ?: account.name,
                address(account.addressLine1, account.addressLine2, account.postalCode, account.city, account.region, account.countryCode, locale),
                account.vatId?.ifBlank { null }, account.companyRegNo?.ifBlank { null },
                account.peppolId?.takeIf { it.isNotBlank() }?.let { "${account.peppolScheme ?: ""}:$it".trimStart(':') },
            ),
            buyer = Party(
                client.name,
                address(client.addressLine1, client.addressLine2, client.postalCode, client.city, client.region, client.countryCode, locale),
                client.vatId?.ifBlank { null }, client.companyRegNo?.ifBlank { null },
                client.peppolId?.takeIf { it.isNotBlank() }?.let { "${client.peppolScheme ?: ""}:$it".trimStart(':') },
            ),
            lines = lines.mapIndexed { i, l ->
                DocumentLine(
                    description = l.description ?: "",
                    quantity = quantity(l.quantity, locale),
                    unitPrice = money(l.unitPriceMinor),
                    amount = money(totals.lineTotals[i]),
                    vat = if (vat) "${l.vatCategoryCode} ${percent(l.vatPercent, locale)}" else null,
                )
            },
            showVatColumn = vat,
            rows = rows,
            vatBreakdown = totals.vat.map { listOf(categoryLabel(it.category, locale), percent(it.percent, locale), money(it.base), money(it.tax)) },
            amountDue = money(r.dueMinor),
            notes = r.notes?.ifBlank { null },
            paymentInstructions = r.paymentInstructions?.ifBlank { null },
            legalNote = when (r.vatMode) {
                "reverse_charge" -> msg("reverseCharge")
                "exempt" -> listOfNotNull(msg("exempt"), r.exemptionReason).joinToString(" ")
                "outside_scope" -> msg("outsideScope")
                else -> null
            },
            footer = r.footer?.ifBlank { null },
            currency = r.currency,
            dueMinor = r.dueMinor,
        )
    }

    fun html(r: InvoicesRecord): String {
        val account = dsl.selectFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(r.accountId)).fetchOne()!!
        val ctx = Context(Locale.forLanguageTag(account.locale), mapOf("doc" to document(r)))
        return engine.process("invoice/invoice", ctx)
    }

    fun pdf(r: InvoicesRecord): ByteArray = render(r, pdfA = false)

    /**
     * The same PDF as PDF/A-3b (archival: fonts embedded, an sRGB output intent), the base for a
     * Factur-X/ZUGFeRD hybrid invoice.
     */
    fun pdfA(r: InvoicesRecord): ByteArray = render(r, pdfA = true)

    private fun render(r: InvoicesRecord, pdfA: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        PdfRendererBuilder().apply {
            useFastMode()
            if (pdfA) {
                usePdfAConformance(PdfRendererBuilder.PdfAConformance.PDFA_3_B)
                useColorProfile(java.awt.color.ICC_Profile.getInstance(java.awt.color.ColorSpace.CS_sRGB).data)
                withProducer("Honest Robin")
            }
            useFont({ javaClass.getResourceAsStream("/fonts/NotoSans-Regular.ttf") }, "Noto Sans", 400, com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle.NORMAL, true)
            useFont({ javaClass.getResourceAsStream("/fonts/NotoSans-Bold.ttf") }, "Noto Sans", 700, com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle.NORMAL, true)
            withHtmlContent(html(r), null)
            toStream(out)
        }.run()
        return out.toByteArray()
    }

    fun filename(r: InvoicesRecord) = "invoice-${(r.number ?: "draft").replace(Regex("[^A-Za-z0-9._-]"), "-")}.pdf"

    private fun money(currency: String, locale: Locale): (Long) -> String {
        val digits = Money.digits(currency)
        val format = NumberFormat.getCurrencyInstance(locale).apply {
            this.currency = Currency.getInstance(currency)
            minimumFractionDigits = digits
            maximumFractionDigits = digits
        }
        return { minor -> format.format(BigDecimal.valueOf(minor, digits)) }
    }

    private fun percent(value: BigDecimal?, locale: Locale): String =
        NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 4 }.format(value ?: BigDecimal.ZERO) + " %"

    private fun quantity(value: BigDecimal, locale: Locale): String =
        NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2; minimumFractionDigits = 0 }.format(value.setScale(2, RoundingMode.HALF_UP))

    private fun address(line1: String?, line2: String?, postal: String?, city: String?, region: String?, country: String?, locale: Locale): List<String> =
        listOfNotNull(
            line1?.ifBlank { null }, line2?.ifBlank { null },
            listOfNotNull(postal?.ifBlank { null }, city?.ifBlank { null }).joinToString(" ").ifBlank { null },
            region?.ifBlank { null },
            country?.takeIf { it.length == 2 }?.let { Locale.of("", it.uppercase()).getDisplayCountry(locale) },
        )

    private fun categoryLabel(code: String, locale: Locale) = messages.getMessage("invoice.vatCategory.$code", null, code, locale) ?: code
}
