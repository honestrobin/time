// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.einvoice

import com.helger.diver.api.coord.DVRCoordinate
import com.helger.phive.api.execute.ValidationExecutionManager
import com.helger.phive.api.executorset.ValidationExecutorSetRegistry
import com.helger.phive.api.validity.IValidityDeterminator
import com.helger.phive.en16931.EN16931Validation
import com.helger.phive.peppol.PeppolValidation
import com.helger.phive.peppol.PeppolValidation2026_05
import com.helger.phive.xml.source.IValidationSourceXML
import com.helger.phive.xml.source.ValidationSourceXML
import com.helger.phive.xrechnung.XRechnungValidation
import com.honestrobin.time.support.IntegrationTest
import com.honestrobin.time.support.TestClient
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

/** AT-5.1: e-invoices pass the official EN 16931, XRechnung and Peppol BIS rules. */
class EInvoiceTest : IntegrationTest() {

    companion object {
        private val registry = ValidationExecutorSetRegistry<IValidationSourceXML>().also {
            EN16931Validation.initEN16931(it)
            XRechnungValidation.initXRechnung(it)
            PeppolValidation.initStandard(it)
        }

        // The newest rule sets in phive-rules 4.6.3; bump them with the dependency.
        val EN16931_CII: DVRCoordinate = EN16931Validation.VID_CII_1316
        val XRECHNUNG_CII: DVRCoordinate = XRechnungValidation.VID_XRECHNUNG_CII_302
        val PEPPOL_INVOICE: DVRCoordinate = PeppolValidation2026_05.VID_OPENPEPPOL_INVOICE_UBL_V3
    }

    /** Errors (not warnings) the official rules find in [xml]. */
    private fun errors(xml: ByteArray, rules: DVRCoordinate): List<String> {
        val ves = registry.getOfID(rules) ?: error("No rule set $rules")
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(xml.inputStream())
        val result = ValidationExecutionManager.executeValidation(IValidityDeterminator.createDefault(), ves, ValidationSourceXML.create("invoice.xml", doc), Locale.US)
        return result.flatMap { it.errorList.allErrors.filter { e -> e.isError }.map { e -> "${e.errorID ?: ""} ${e.getErrorText(Locale.US)}" } }
    }

    private class Setup(val admin: TestClient, val client: UUID)

    /** A German seller and a French buyer, everything an e-invoice needs. */
    private fun setup(): Setup {
        val admin = signup(accountName = "Fjordlicht GmbH")
        admin.patch(
            "/api/v1/account",
            mapOf(
                "legal_name" to "Fjordlicht GmbH", "address_line1" to "Hafenstraße 12", "postal_code" to "20457", "city" to "Hamburg",
                "country_code" to "DE", "vat_id" to "DE123456789", "iban" to "DE89370400440532013000", "bic" to "COBADEFFXXX",
                // Peppol participant IDs by VAT number: 9930 is Germany, 9957 France.
                "peppol_scheme" to "9930", "peppol_id" to "DE123456789",
            ),
        ).expect(200)
        admin.patch(
            "/api/v1/invoice_settings",
            mapOf("invoice_contact_name" to "Ada Admin", "invoice_contact_email" to "billing@fjordlicht.test", "invoice_contact_phone" to "+49 40 123456"),
        ).expect(200)
        val client = admin.post(
            "/api/v1/clients",
            mapOf("name" to "Atelier Bleu SARL", "currency" to "EUR", "address_line1" to "12 rue de la Paix", "postal_code" to "75002", "city" to "Paris", "country_code" to "FR", "vat_id" to "FR40303265045", "peppol_scheme" to "9957", "peppol_id" to "FR40303265045"),
        ).expect(201).id()
        admin.post("/api/v1/clients/$client/contacts", mapOf("name" to "Claire Martin", "email" to "factures@atelierbleu.test", "is_invoice_recipient" to true)).expect(201)
        return Setup(admin, client)
    }

    private fun issuedInvoice(s: Setup, extra: Map<String, Any?> = emptyMap(), lines: List<Map<String, Any?>>? = null): UUID {
        val id = s.admin.post(
            "/api/v1/invoices",
            mapOf(
                "client_id" to s.client, "vat_mode" to "standard", "buyer_reference" to "04011000-12345-34", "purchase_order" to "PO-2026-118",
                "discount_percent" to 10, "period_start" to "2026-09-01", "period_end" to "2026-09-30",
                "lines" to (lines ?: listOf(
                    mapOf("description" to "Design, September", "quantity" to 12.5, "unit_price" to 9_500, "vat_category_code" to "S", "vat_percent" to 19),
                    mapOf("description" to "Hosting", "quantity" to 1, "unit_price" to 4_900, "vat_category_code" to "S", "vat_percent" to 19),
                    mapOf("description" to "Printed guide", "kind" to "product", "quantity" to 3, "unit_price" to 1_250, "vat_category_code" to "S", "vat_percent" to 7),
                )),
            ) + extra,
        ).expect(201).id()
        s.admin.post("/api/v1/invoices/$id/mark_sent").expect(200)
        s.admin.post("/api/v1/invoices/$id/payments", mapOf("amount" to 10_000, "paid_on" to "2026-10-01")).expect(201)
        return id
    }

    @Test
    fun `XRechnung, Peppol BIS and Factur-X pass the official rules (AT-5_1)`() {
        val s = setup()
        val invoice = issuedInvoice(s)
        val ready = s.admin.get("/api/v1/invoices/$invoice/einvoice/readiness").expect(200).body
        assertThat(ready.map { it["format"].asText() to it["ready"].asBoolean() }).containsExactly("facturx" to true, "xrechnung" to true, "peppol" to true)

        val xrechnung = s.admin.get("/api/v1/invoices/$invoice/einvoice", mapOf("format" to "xrechnung")).expect(200)
        assertThat(xrechnung.headers["Content-Disposition"]!!.single()).contains("-xrechnung.xml")
        assertThat(errors(xrechnung.bytes, XRECHNUNG_CII)).isEmpty()
        assertThat(errors(xrechnung.bytes, EN16931_CII)).isEmpty()

        val peppol = s.admin.get("/api/v1/invoices/$invoice/einvoice", mapOf("format" to "peppol")).expect(200).bytes
        assertThat(peppol.toString(Charsets.UTF_8)).contains("urn:fdc:peppol.eu:2017:poacc:billing:3.0")
        assertThat(errors(peppol, PEPPOL_INVOICE)).isEmpty()

        // Factur-X: a PDF/A-3 with the CII XML inside, which passes EN 16931.
        val pdf = s.admin.get("/api/v1/invoices/$invoice/einvoice", mapOf("format" to "facturx")).expect(200).bytes
        val (xml, metadata) = Loader.loadPDF(pdf).use { doc ->
            val names = PDDocumentNameDictionary(doc.documentCatalog).embeddedFiles as PDEmbeddedFilesNameTreeNode
            val file = names.names?.get("factur-x.xml") ?: names.kids.flatMap { it.names.entries }.first { it.key == "factur-x.xml" }.value
            file.embeddedFile.toByteArray() to String(doc.documentCatalog.metadata.toByteArray())
        }
        assertThat(metadata).contains("<pdfaid:part>3</pdfaid:part>").contains("factur-x")
        assertThat(errors(xml, EN16931_CII)).isEmpty()
        assertThat(pdfaFailures(pdf)).isEmpty()
    }

    /** What veraPDF finds wrong with [pdf] as PDF/A-3b, the level Factur-X requires. */
    private fun pdfaFailures(pdf: ByteArray): List<String> {
        org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider.initialise()
        val flavour = org.verapdf.pdfa.flavours.PDFAFlavour.PDFA_3_B
        val foundry = org.verapdf.pdfa.Foundries.defaultInstance()
        return foundry.createParser(java.io.ByteArrayInputStream(pdf), flavour).use { parser ->
            val result = foundry.createValidator(flavour, false).validate(parser)
            val failures = result.testAssertions.filter { it.status == org.verapdf.pdfa.results.TestAssertion.Status.FAILED }
                .map { "${it.ruleId.clause}-${it.ruleId.testNumber}: ${it.message}" }
                .distinct()
            failures.ifEmpty { if (result.isCompliant && result.pdfaFlavour == flavour) emptyList() else listOf("not compliant as ${result.pdfaFlavour}") }
        }
    }

    @Test
    fun `what's missing is said in plain words, per format`() {
        val s = setup()
        val draft = s.admin.post("/api/v1/invoices", mapOf("client_id" to s.client, "vat_mode" to "reverse_charge", "lines" to listOf(mapOf("description" to "Work", "quantity" to 1, "unit_price" to 1000)))).expect(201).id()
        val notReady = s.admin.get("/api/v1/invoices/$draft/einvoice/readiness").expect(200).body.associateBy { it["format"].asText() }
        assertThat(notReady["facturx"]!!["problems"].map { it["message"].asText() }).anyMatch { it.contains("a draft has no number") }
        assertThat(notReady["xrechnung"]!!["problems"].map { it["field"].asText() }).contains("buyer_reference")
        s.admin.get("/api/v1/invoices/$draft/einvoice", mapOf("format" to "xrechnung")).expectError(422, "einvoice_incomplete")

        // Reverse charge needs the client's VAT ID.
        s.admin.patch("/api/v1/clients/${s.client}", mapOf("vat_id" to "")).expect(200)
        s.admin.post("/api/v1/invoices/$draft/mark_sent").let { assertThat(it.status).isIn(200, 422) }
        val rc = s.admin.get("/api/v1/invoices/$draft/einvoice/readiness").expect(200).body.first { it["format"].asText() == "facturx" }
        assertThat(rc["problems"].map { it["field"].asText() }).contains("vat_id")
    }

    @Test
    fun `with hybrid PDFs on, the invoice PDF carries the XML, and with incomplete data it stays a plain PDF`() {
        val s = setup()
        s.admin.patch("/api/v1/invoice_settings", mapOf("einvoice_hybrid_pdf" to true)).expect(200)
        val invoice = issuedInvoice(s)
        val pdf = s.admin.get("/api/v1/invoices/$invoice/pdf").expect(200).bytes
        assertThat(Loader.loadPDF(pdf).use { PDDocumentNameDictionary(it.documentCatalog).embeddedFiles != null }).isTrue()
        // A German account follows the EU default when the setting is cleared.
        assertThat(s.admin.patch("/api/v1/invoice_settings", mapOf("einvoice_hybrid_pdf" to null)).expect(200)["einvoice_hybrid_pdf_active"].asBoolean()).isTrue()
        // Without the client's country, there is no e-invoice, and the PDF is plain.
        s.admin.patch("/api/v1/clients/${s.client}", mapOf("country_code" to "")).expect(200)
        val plain = s.admin.get("/api/v1/invoices/$invoice/pdf").expect(200).bytes
        assertThat(Loader.loadPDF(plain).use { PDDocumentNameDictionary(it.documentCatalog).embeddedFiles }).isNull()
    }
}
