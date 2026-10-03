// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.invoicing

import com.honestrobin.time.support.IntegrationTest
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import javax.imageio.ImageIO

/**
 * Not a check: renders sample invoices to build/invoice-samples/ (PDF and PNG) for a design review.
 * Run with INVOICE_SAMPLES=1 ./gradlew :backend:test --tests '*InvoicePdfSample'.
 */
@EnabledIfEnvironmentVariable(named = "INVOICE_SAMPLES", matches = ".+")
class InvoicePdfSample : IntegrationTest() {
    @Test
    fun `render samples`() {
        val out = File("build/invoice-samples").apply { mkdirs() }
        val admin = signup(accountName = "Fjord & Pine Studio")
        admin.patch(
            "/api/v1/account",
            mapOf(
                "legal_name" to "Fjord & Pine Studio GmbH", "address_line1" to "Torstraße 140", "postal_code" to "10119", "city" to "Berlin",
                "country_code" to "DE", "vat_id" to "DE812345678", "company_reg_no" to "HRB 123456 B",
            ),
        ).expect(200)
        admin.patch("/api/v1/invoice_settings", mapOf("payment_instructions" to "Bank transfer to Fjord & Pine Studio GmbH\nIBAN DE89 3704 0044 0532 0130 00 · BIC COBADEFFXXX", "footer" to "Fjord & Pine Studio GmbH · Torstraße 140, 10119 Berlin · hello@fjordpine.example")).expect(200)

        fun client(name: String, currency: String, country: String, vat: String?) = admin.post(
            "/api/v1/clients",
            mapOf("name" to name, "currency" to currency, "address_line1" to "1 Harbour Street", "city" to "Wellington", "postal_code" to "6011", "country_code" to country, "vat_id" to vat),
        ).expect(201).id()

        val nz = client("Kiwi Outdoor Ltd", "NZD", "NZ", "123-456-789")
        val gst = admin.post(
            "/api/v1/invoices",
            mapOf(
                "client_id" to nz, "subject" to "Website relaunch, March", "purchase_order" to "PO-7781", "tax1_name" to "GST", "tax1_percent" to 15,
                "notes" to "Thank you for your business.",
                "lines" to listOf(
                    mapOf("description" to "Design — homepage and booking flow", "quantity" to 32.5, "unit_price" to 14_500),
                    mapOf("description" to "Development — booking form, payment step and confirmation emails", "quantity" to 41.25, "unit_price" to 16_000),
                    mapOf("description" to "Project management", "quantity" to 6, "unit_price" to 12_000),
                    mapOf("kind" to "expense", "description" to "Travel to Wellington — flights", "quantity" to 1, "unit_price" to 48_900, "tax1_applies" to false),
                ),
            ),
        ).expect(201)
        admin.post("/api/v1/invoices/${gst.id()}/mark_sent").expect(200)

        val fr = client("Atelier Lumière SAS", "EUR", "FR", "FR40303265045")
        val rc = admin.post(
            "/api/v1/invoices",
            mapOf(
                "client_id" to fr, "vat_mode" to "reverse_charge", "buyer_reference" to "LUM-2026-04",
                "lines" to listOf(mapOf("description" to "Brand identity workshop (2 days)", "quantity" to 2, "unit_price" to 180_000)),
            ),
        ).expect(201)
        admin.post("/api/v1/invoices/${rc.id()}/mark_sent").expect(200)

        listOf("gst" to gst.id(), "reverse-charge" to rc.id()).forEach { (name, id) ->
            val bytes = admin.get("/api/v1/invoices/$id/pdf").expect(200).bytes
            File(out, "$name.pdf").writeBytes(bytes)
            Loader.loadPDF(bytes).use { doc -> ImageIO.write(PDFRenderer(doc).renderImageWithDPI(0, 90f), "png", File(out, "$name.png")) }
        }
    }
}
