// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.einvoice

import com.helger.cii.d16b.CIID16BCrossIndustryInvoiceTypeMarshaller
import com.helger.diagnostics.error.list.ErrorList
import com.helger.en16931.cii2ubl.en2017.CIID16BToUBL21Converter
import com.helger.ubl21.UBL21Marshaller
import com.honestrobin.time.db.Tables.ACCOUNTS
import com.honestrobin.time.db.Tables.CLIENTS
import com.honestrobin.time.db.Tables.CLIENT_CONTACTS
import com.honestrobin.time.db.Tables.INVOICE_LINES
import com.honestrobin.time.db.tables.records.AccountsRecord
import com.honestrobin.time.db.tables.records.ClientsRecord
import com.honestrobin.time.db.tables.records.InvoiceLinesRecord
import com.honestrobin.time.db.tables.records.InvoicesRecord
import com.honestrobin.time.invoicing.InvoiceMath
import com.honestrobin.time.invoicing.InvoicePdf
import com.honestrobin.time.invoicing.InvoiceService
import com.honestrobin.time.platform.Money
import com.honestrobin.time.platform.security.Member
import com.honestrobin.time.platform.web.ApiException
import org.jooq.DSLContext
import org.mustangproject.Allowance
import org.mustangproject.BankDetails
import org.mustangproject.Contact
import org.mustangproject.Invoice
import org.mustangproject.Item
import org.mustangproject.Product
import org.mustangproject.SchemedID
import org.mustangproject.TradeParty
import org.mustangproject.ZUGFeRD.Profiles
import org.mustangproject.ZUGFeRD.TransactionCalculator
import org.mustangproject.ZUGFeRD.ZUGFeRD2PullProvider
import org.mustangproject.ZUGFeRD.ZUGFeRDExporterFromPDFA
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.UUID

/** The e-invoice formats (spec §7.2). */
enum class EInvoiceFormat(val key: String, val label: String, val extension: String, val contentType: String) {
    /** Factur-X / ZUGFeRD 2, EN 16931 profile: a PDF/A-3 with the CII XML inside. */
    FACTURX("facturx", "Factur-X / ZUGFeRD", "pdf", "application/pdf"),

    /** XRechnung (CII), for public-sector buyers in Germany. */
    XRECHNUNG("xrechnung", "XRechnung", "xml", "application/xml"),

    /** Peppol BIS Billing 3.0 (UBL), the format of the Peppol network (EU, Australia, New Zealand, Singapore, Japan…). */
    PEPPOL("peppol", "Peppol BIS Billing 3.0", "xml", "application/xml");

    companion object {
        fun of(key: String): EInvoiceFormat = entries.firstOrNull { it.key == key }
            ?: throw com.honestrobin.time.platform.web.ValidationException("format", "Choose facturx, xrechnung or peppol")
    }
}

/** Something missing before an e-invoice can be made, said so a person can fix it. */
data class EInvoiceProblem(
    /** account, client or invoice: where to fix it. */
    val where: String,
    val field: String,
    val message: String,
)

data class EInvoiceReadiness(val format: String, val ready: Boolean, val problems: List<EInvoiceProblem>)

class EInvoiceFile(val bytes: ByteArray, val filename: String, val contentType: String)

/**
 * E-invoices (spec §7): the EN 16931 data of an invoice, as Factur-X/ZUGFeRD, XRechnung or
 * Peppol BIS. Mustangproject writes the CII XML and the hybrid PDF; the Peppol UBL is converted
 * from the same CII, so all three say the same thing. What a format needs is checked first, with
 * messages that say what to fill in where, and the totals must come out exactly as on the invoice.
 */
@Service
class EInvoiceService(
    private val dsl: DSLContext,
    private val invoices: InvoiceService,
    private val pdf: InvoicePdf,
) {
    private class Data(val invoice: InvoicesRecord, val account: AccountsRecord, val client: ClientsRecord, val lines: List<InvoiceLinesRecord>, val recipientEmail: String?)

    private fun load(r: InvoicesRecord): Data {
        val account = dsl.selectFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(r.accountId)).fetchOne()!!
        val client = dsl.selectFrom(CLIENTS).where(CLIENTS.ID.eq(r.clientId)).fetchOne()!!
        val lines = dsl.selectFrom(INVOICE_LINES).where(INVOICE_LINES.INVOICE_ID.eq(r.id)).orderBy(INVOICE_LINES.POSITION).fetch()
        val email = dsl.select(CLIENT_CONTACTS.EMAIL).from(CLIENT_CONTACTS)
            .where(CLIENT_CONTACTS.CLIENT_ID.eq(client.id)).and(CLIENT_CONTACTS.EMAIL.isNotNull)
            .orderBy(CLIENT_CONTACTS.IS_INVOICE_RECIPIENT.desc(), CLIENT_CONTACTS.CREATED_AT).limit(1).fetchOne()?.value1()
        return Data(r, account, client, lines, email)
    }

    @Transactional(readOnly = true)
    fun readiness(m: Member, invoiceId: UUID, format: EInvoiceFormat): EInvoiceReadiness {
        invoices.requireAccess(m)
        val problems = problems(load(invoices.load(invoiceId)), format)
        return EInvoiceReadiness(format.key, problems.isEmpty(), problems)
    }

    @Transactional(readOnly = true)
    fun generate(m: Member, invoiceId: UUID, format: EInvoiceFormat): EInvoiceFile {
        invoices.requireAccess(m)
        return generate(invoices.load(invoiceId), format)
    }

    fun generate(r: InvoicesRecord, format: EInvoiceFormat): EInvoiceFile {
        val d = load(r)
        val problems = problems(d, format)
        if (problems.isNotEmpty()) {
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "einvoice_incomplete", problems.joinToString(" ") { it.message }, details = mapOf("problems" to problems))
        }
        val model = model(d, format)
        val base = "invoice-${r.number!!.replace(Regex("[^A-Za-z0-9._-]"), "-")}"
        return when (format) {
            EInvoiceFormat.XRECHNUNG -> EInvoiceFile(cii(model, "XRechnung"), "$base-xrechnung.xml", format.contentType)
            EInvoiceFormat.PEPPOL -> EInvoiceFile(peppol(cii(model, "EN16931")), "$base-peppol.xml", format.contentType)
            EInvoiceFormat.FACTURX -> EInvoiceFile(facturX(r, model), "$base.pdf", format.contentType)
        }
    }

    /** The invoice PDF with its Factur-X XML inside, or null if the invoice lacks e-invoice data. */
    fun hybridPdfOrNull(r: InvoicesRecord): ByteArray? {
        val d = load(r)
        if (problems(d, EInvoiceFormat.FACTURX).isNotEmpty()) return null
        return facturX(r, model(d, EInvoiceFormat.FACTURX))
    }

    /**
     * The PDF of an issued invoice as clients get it: a Factur-X hybrid when the account has them
     * on and the invoice has the data, otherwise the plain PDF.
     */
    fun invoicePdf(r: InvoicesRecord): ByteArray {
        if (r.number != null && r.state != "draft") {
            val account = dsl.selectFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(r.accountId)).fetchOne()!!
            if (hybridEnabled(account)) {
                try {
                    hybridPdfOrNull(r)?.let { return it }
                } catch (e: Exception) {
                    log.warn("Invoice {} went out as a plain PDF: the hybrid failed ({})", r.id, e.message)
                }
            }
        }
        return pdf.pdf(r)
    }

    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    /** Whether invoices of this account go out as hybrid PDFs: the admin's choice, or on where the account's country is in the EU. */
    fun hybridEnabled(account: AccountsRecord): Boolean = account.einvoiceHybridPdf ?: (account.countryCode?.uppercase() in EU)

    // ---- checks ----------------------------------------------------------------

    private fun problems(d: Data, format: EInvoiceFormat): List<EInvoiceProblem> {
        val r = d.invoice
        val a = d.account
        val c = d.client
        val p = mutableListOf<EInvoiceProblem>()
        fun account(field: String, message: String) = p.add(EInvoiceProblem("account", field, message))
        fun client(field: String, message: String) = p.add(EInvoiceProblem("client", field, message))
        fun invoice(field: String, message: String) = p.add(EInvoiceProblem("invoice", field, message))

        if (r.number == null) invoice("number", "Send or mark the invoice as sent first: a draft has no number yet.")
        if (r.state == "void") invoice("state", "A void invoice isn't sent as an e-invoice.")
        if (d.lines.isEmpty()) invoice("lines", "Add at least one line.")
        if (Money.digits(r.currency) > 2) invoice("currency", "E-invoices carry at most two decimals, so ${r.currency} amounts can't be expressed exactly.")
        val categories = categories(d)
        if (categories == null) {
            invoice("tax", "E-invoices need VAT categories, or one tax on every line. Switch the invoice's tax to VAT categories.")
        }

        if (a.addressLine1.isNullOrBlank() || a.city.isNullOrBlank() || a.postalCode.isNullOrBlank()) account("address", "Add your company's address in Account settings.")
        if (a.countryCode.isNullOrBlank()) account("country_code", "Add your company's country in Account settings.")
        val needsSellerTaxId = categories.orEmpty().any { it != "O" }
        if (needsSellerTaxId && a.vatId.isNullOrBlank() && a.companyRegNo.isNullOrBlank()) {
            account("vat_id", "Add your VAT ID (or tax number) in Account settings.")
        }
        if (c.countryCode.isNullOrBlank()) client("country_code", "Add ${c.name}'s country.")
        if (categories.orEmpty().any { it == "AE" || it == "K" } && c.vatId.isNullOrBlank()) {
            client("vat_id", "${c.name}'s VAT ID is missing: reverse charge and intra-community supplies need it.")
        }
        if ("E" in categories.orEmpty() && r.exemptionReason.isNullOrBlank()) invoice("exemption_reason", "Say why the invoice is exempt from VAT.")

        val buyerAddress = c.peppolId?.isNotBlank() == true || d.recipientEmail != null
        when (format) {
            EInvoiceFormat.XRECHNUNG -> {
                if (r.buyerReference.isNullOrBlank()) invoice("buyer_reference", "Add the buyer reference (Leitweg-ID) that ${c.name} gave you.")
                if (a.invoiceContactName.isNullOrBlank() || a.invoiceContactEmail.isNullOrBlank() || a.invoiceContactPhone.isNullOrBlank()) {
                    account("invoice_contact", "XRechnung needs a contact on your side: add a name, email and phone in Invoice settings.")
                }
                if (a.iban.isNullOrBlank()) account("iban", "Add your IBAN in Account settings: XRechnung needs payment instructions.")
                if (c.city.isNullOrBlank() || c.postalCode.isNullOrBlank()) client("address", "Add ${c.name}'s city and postal code.")
                if (!buyerAddress) client("peppol_id", "Add ${c.name}'s Peppol ID, or a contact with an email address.")
            }
            EInvoiceFormat.PEPPOL -> {
                if (r.buyerReference.isNullOrBlank() && r.purchaseOrder.isNullOrBlank()) invoice("buyer_reference", "Add a buyer reference or purchase order number: Peppol needs one of them.")
                // The Peppol network routes by participant ID; it doesn't accept email addresses.
                if (a.peppolId.isNullOrBlank()) account("peppol_id", "Add your Peppol ID (scheme and identifier) in Account settings.")
                if (c.peppolId.isNullOrBlank()) client("peppol_id", "Add ${c.name}'s Peppol ID (scheme and identifier).")
            }
            EInvoiceFormat.FACTURX -> Unit
        }
        return p
    }

    /** UNCL 5305 categories per line, or null when the invoice's taxes can't be expressed in EN 16931. */
    private fun categories(d: Data): List<String>? {
        val r = d.invoice
        if (r.vatMode != null) return d.lines.map { it.vatCategoryCode ?: "S" }
        // Named taxes: one tax on every line reads as a single VAT rate; none at all as zero-rated.
        if (r.tax2Percent != null && d.lines.any { it.tax2Applies }) return null
        val taxed = d.lines.count { it.tax1Applies && r.tax1Percent != null }
        return when (taxed) {
            d.lines.size -> d.lines.map { "S" }
            0 -> if (r.tax1Percent == null) d.lines.map { "Z" } else null
            else -> null
        }
    }

    // ---- the model -------------------------------------------------------------

    private fun date(d: LocalDate): Date = Date.from(d.atStartOfDay(ZoneId.systemDefault()).toInstant())

    private fun model(d: Data, format: EInvoiceFormat): Invoice {
        val r = d.invoice
        val a = d.account
        val c = d.client
        val currency = r.currency
        fun amount(minor: Long) = Money.fromMinor(minor, currency).setScale(2)
        val categories = categories(d)!!
        val outsideScope = categories.all { it == "O" }

        val seller = TradeParty(a.legalName?.ifBlank { null } ?: a.name, a.addressLine1 ?: "", a.postalCode ?: "", a.city ?: "", a.countryCode!!.uppercase())
        a.addressLine2?.takeIf { it.isNotBlank() }?.let { seller.setAdditionalAddress(it) }
        if (!outsideScope) {
            a.vatId?.takeIf { it.isNotBlank() }?.let { seller.addVATID(it) }
            a.companyRegNo?.takeIf { it.isNotBlank() }?.let { seller.addTaxID(it) }
        }
        if (!a.invoiceContactName.isNullOrBlank() || !a.invoiceContactEmail.isNullOrBlank()) {
            seller.setContact(Contact(a.invoiceContactName ?: seller.name, a.invoiceContactPhone ?: "", a.invoiceContactEmail ?: ""))
        }
        when {
            a.peppolId?.isNotBlank() == true -> seller.addUriUniversalCommunicationID(SchemedID(a.peppolScheme ?: "0088", a.peppolId))
            !a.invoiceContactEmail.isNullOrBlank() -> seller.setEmail(a.invoiceContactEmail)
        }
        a.iban?.takeIf { it.isNotBlank() }?.let { iban ->
            val bank = BankDetails(iban.replace(" ", ""), a.bic?.replace(" ", "")?.ifBlank { null })
            // 58 is a SEPA credit transfer, 30 any other credit transfer.
            bank.setPaymentMeansCode(if (currency == "EUR") "58" else "30")
            seller.addBankDetails(bank)
        }

        val buyer = TradeParty(c.name, c.addressLine1 ?: "", c.postalCode ?: "", c.city ?: "", c.countryCode!!.uppercase())
        c.addressLine2?.takeIf { it.isNotBlank() }?.let { buyer.setAdditionalAddress(it) }
        if (!outsideScope) c.vatId?.takeIf { it.isNotBlank() }?.let { buyer.addVATID(it) }
        when {
            c.peppolId?.isNotBlank() == true -> buyer.addUriUniversalCommunicationID(SchemedID(c.peppolScheme ?: "0088", c.peppolId))
            d.recipientEmail != null -> buyer.setEmail(d.recipientEmail)
        }

        val inv = Invoice()
            .setNumber(r.number)
            .setIssueDate(date(r.issueDate))
            .setDeliveryDate(date(r.periodEnd ?: r.issueDate))
            .setCurrency(currency)
            .setSender(seller)
            .setRecipient(buyer)
        r.dueDate?.let { inv.setDueDate(date(it)) }
        if (r.periodStart != null && r.periodEnd != null) inv.setDetailedDeliveryPeriod(date(r.periodStart), date(r.periodEnd))
        r.buyerReference?.takeIf { it.isNotBlank() }?.let { inv.setReferenceNumber(it) }
        r.purchaseOrder?.takeIf { it.isNotBlank() }?.let { inv.setBuyerOrderReferencedDocumentID(it) }
        inv.setPaymentTermDescription(r.paymentTerms?.ifBlank { null } ?: r.paymentTermsDays?.let { "Payable within $it days" } ?: "Payable on receipt")
        r.notes?.takeIf { it.isNotBlank() }?.let { inv.addNote(it) }
        if (r.paidMinor > 0) inv.setTotalPrepaidAmount(amount(r.paidMinor))

        val vatPercent: (InvoiceLinesRecord, String) -> BigDecimal = { l, cat ->
            when {
                r.vatMode != null -> if (InvoiceMath.VAT_CATEGORIES.getValue(cat)) (l.vatPercent ?: BigDecimal.ZERO) else BigDecimal.ZERO
                cat == "S" -> r.tax1Percent
                else -> BigDecimal.ZERO
            }
        }
        d.lines.forEachIndexed { i, l ->
            val cat = categories[i]
            val text = l.description?.trim().orEmpty()
            val name = text.lineSequence().firstOrNull()?.take(120)?.ifBlank { null } ?: "Service"
            val unit = if (l.kind == "service" && (l.taskId != null || l.membershipId != null)) "HUR" else "C62"
            val product = Product(name, text.takeIf { it != name } ?: "", unit, vatPercent(l, cat).stripTrailingZeros())
            product.setTaxCategoryCode(cat)
            exemption(cat, r.exemptionReason)?.let { product.setTaxExemptionReason(it) }
            inv.addItem(Item(product, Money.fromMinor(l.unitPriceMinor, currency), l.quantity.stripTrailingZeros()))
        }

        // The discount, split per VAT group as the invoice computes it (EN 16931 document-level allowances).
        val totals = invoices.totals(r, d.lines)
        if (totals.discount > 0) {
            val groups = if (totals.vat.isNotEmpty()) totals.vat.map { Triple(it.category, it.percent, it) } else null
            if (groups != null) {
                val lineGroups = d.lines.indices.groupBy { categories[it] to vatPercent(d.lines[it], categories[it]).stripTrailingZeros() }
                lineGroups.forEach { (key, idx) ->
                    val base = idx.sumOf { totals.lineTotals[it] }
                    val discount = InvoiceMath.percentOf(base, r.discountPercent)
                    if (discount > 0) inv.addAllowance(allowance(amount(discount), key.first, key.second, r.exemptionReason))
                }
            } else {
                inv.addAllowance(allowance(amount(totals.discount), categories.first(), vatPercent(d.lines.first(), categories.first()).stripTrailingZeros(), r.exemptionReason))
            }
        }

        // An e-invoice that disagrees with the invoice it stands for is worse than none.
        val calc = TransactionCalculator(inv)
        val expectedTotal = amount(r.totalMinor)
        if (calc.grandTotal.compareTo(expectedTotal) != 0) {
            throw ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY, "einvoice_mismatch",
                "The e-invoice total (${calc.grandTotal}) would differ from the invoice total ($expectedTotal), so it isn't made. This happens with fractional quantities in currencies without decimals.",
            )
        }
        return inv
    }

    private fun allowance(amount: BigDecimal, category: String, percent: BigDecimal, reason: String?): Allowance {
        val a = Allowance(amount)
        a.setReason("Discount")
        a.setTaxCategoryCode(category)
        a.setTaxPercent(percent)
        exemption(category, reason)?.let { a.setTaxExemptionReason(it) }
        return a
    }

    /** EN 16931 wants a reason for every category without VAT. */
    private fun exemption(category: String, reason: String?): String? = when (category) {
        "E" -> reason?.ifBlank { null } ?: "Exempt from VAT"
        "AE" -> "Reverse charge"
        "K" -> "Intra-community supply"
        "G" -> "Export outside the EU"
        "O" -> "Not subject to VAT"
        else -> null
    }

    // ---- formats -----------------------------------------------------------------

    private fun cii(inv: Invoice, profile: String): ByteArray {
        val provider = ZUGFeRD2PullProvider()
        provider.setProfile(Profiles.getByName(profile))
        provider.generateXML(inv)
        return provider.xml
    }

    private fun peppol(cii: ByteArray): ByteArray {
        val parsed = CIID16BCrossIndustryInvoiceTypeMarshaller().read(cii)
            ?: throw IllegalStateException("Mustang wrote CII that doesn't parse")
        val errors = ErrorList()
        val ubl = CIID16BToUBL21Converter()
            .setCustomizationID("urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0")
            .setProfileID("urn:fdc:peppol.eu:2017:poacc:billing:01:1.0")
            .convertToInvoice(parsed, errors)
        check(ubl != null && !errors.containsAtLeastOneError()) { "CII to UBL conversion failed: ${errors.allErrors.joinToString { it.toString() }}" }
        return UBL21Marshaller.invoice().getAsBytes(ubl) ?: error("UBL couldn't be written")
    }

    private fun facturX(r: InvoicesRecord, inv: Invoice): ByteArray {
        val out = ByteArrayOutputStream()
        ZUGFeRDExporterFromPDFA().load(pdf.pdfA(r)).apply {
            setProducer("Honest Robin")
            setCreator("Honest Robin")
            setProfile(Profiles.getByName("EN16931"))
            setTransaction(inv)
        }.export(out)
        return out.toByteArray()
    }

    companion object {
        /** EU member states (ISO 3166-1 alpha-2; Greece is GR here, EL in VAT IDs). */
        val EU = setOf("AT", "BE", "BG", "CY", "CZ", "DE", "DK", "EE", "ES", "FI", "FR", "GR", "HR", "HU", "IE", "IT", "LT", "LU", "LV", "MT", "NL", "PL", "PT", "RO", "SE", "SI", "SK")
    }
}
