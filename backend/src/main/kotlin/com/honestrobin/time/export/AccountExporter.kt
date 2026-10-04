// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.export

import tools.jackson.core.JsonEncoding
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.SerializationFeature
import com.honestrobin.time.db.Tables.ACCOUNTS
import com.honestrobin.time.db.Tables.CLIENTS
import com.honestrobin.time.db.Tables.CLIENT_CONTACTS
import com.honestrobin.time.db.Tables.FILES
import com.honestrobin.time.db.Tables.INVOICES
import com.honestrobin.time.db.Tables.MEMBERSHIPS
import com.honestrobin.time.db.Tables.PROJECTS
import com.honestrobin.time.db.Tables.TASKS
import com.honestrobin.time.files.FileStorage
import com.honestrobin.time.invoicing.InvoicePdf
import com.honestrobin.time.platform.Money
import com.honestrobin.time.platform.security.Member
import com.honestrobin.time.platform.security.Role
import com.honestrobin.time.reports.Column
import com.honestrobin.time.reports.ColumnType
import com.honestrobin.time.reports.Csv
import com.honestrobin.time.reports.ExportRequest
import com.honestrobin.time.reports.ReportExportService
import com.honestrobin.time.reports.ReportFilter
import com.honestrobin.time.reports.ReportTable
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.stereotype.Component
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ExportedTable(val name: String, val rows: Long, val sha256: String)

data class ExportedFile(val id: UUID, val path: String, val size: Long, val sha256: String)

data class ExportManifest(
    val format: String,
    val version: Int,
    val exportedAt: Instant,
    val appVersion: String,
    val accountId: UUID,
    val accountName: String,
    val tables: List<ExportedTable>,
    val files: List<ExportedFile>,
    val excluded: Map<String, String>,
)

/**
 * Writes an account's export zip. Call inside one read-only, repeatable-read transaction with the
 * account's database context, so every file shows the same moment.
 */
@Component
class AccountExporter(
    private val dsl: DSLContext,
    private val storage: FileStorage,
    private val pdf: InvoicePdf,
    private val reports: ReportExportService,
    private val json: ObjectMapper,
    private val build: ObjectProvider<BuildProperties>,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun write(accountId: UUID, target: Path): ExportManifest {
        val account = dsl.selectFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(accountId)).fetchOne() ?: error("Account $accountId not visible")
        Files.newOutputStream(target).buffered().use { raw ->
            ZipOutputStream(raw).use { zip ->
                val tables = ExportFormat.TABLES.map { t -> entry(zip, "data/${t.name}.json") { out -> writeTable(t, accountId, out) }.let { (rows, sha) -> ExportedTable(t.name, rows, sha) } }
                writeCsvs(zip, accountId)
                writeInvoicePdfs(zip)
                val files = writeFiles(zip)
                val manifest = ExportManifest(
                    format = ExportFormat.FORMAT, version = ExportFormat.VERSION, exportedAt = Instant.now(clock),
                    appVersion = build.ifAvailable?.version ?: "dev",
                    accountId = accountId, accountName = account.name, tables = tables, files = files,
                    excluded = ExportFormat.EXCLUDED.mapKeys { it.key.name },
                )
                entry(zip, "README.txt") { out -> out.write(readme(manifest).toByteArray()); 0 }
                entry(zip, "manifest.json") { out -> json.writer(SerializationFeature.INDENT_OUTPUT).writeValue(NonClosing(out), manifest); 0 }
                return manifest
            }
        }
    }

    /** Writes one zip entry; returns the block's row count and the entry's SHA-256. */
    private fun entry(zip: ZipOutputStream, name: String, block: (OutputStream) -> Long): Pair<Long, String> {
        zip.putNextEntry(ZipEntry(name))
        val digest = MessageDigest.getInstance("SHA-256")
        val out = DigestOutputStream(NonClosing(zip), digest)
        val rows = block(out)
        out.flush()
        zip.closeEntry()
        return rows to HexFormat.of().formatHex(digest.digest())
    }

    private fun writeTable(t: ExportTable, accountId: UUID, out: OutputStream): Long {
        val fields = t.fields
        var rows = 0L
        json.writer().with(RowPerLinePrinter()).createGenerator(NonClosing(out), JsonEncoding.UTF8).use { g ->
            g.writeStartArray()
            dsl.select(fields).from(t.table).where(t.rows(accountId))
                .orderBy(t.table.primaryKey!!.fields)
                .fetchSize(1000).fetchLazy().use { cursor ->
                    for (r in cursor) {
                        g.writeStartObject()
                        fields.forEachIndexed { i, f ->
                            g.writeName(f.name)
                            ExportCodec.write(g, r.get(i))
                        }
                        g.writeEndObject()
                        rows++
                    }
                }
            g.writeEndArray()
        }
        return rows
    }

    /** Spreadsheets for people; the JSON files are the complete record. */
    private fun writeCsvs(zip: ZipOutputStream, accountId: UUID) {
        val admin = Member(UUID(0, 0), accountId, UUID(0, 0), Role.ADMIN, "Export", canSeeRates = true, canManageProjects = true, canManageInvoices = true, accountStatus = "active")
        entry(zip, "csv/time-entries.csv") { out -> reports.write(admin, ExportRequest("detailed", "csv", null, ReportFilter()), NonClosing(out)); 0 }
        entry(zip, "csv/expenses.csv") { out -> reports.write(admin, ExportRequest("expense_items", "csv", null, ReportFilter()), NonClosing(out)); 0 }
        val text = { h: String -> Column(h, ColumnType.TEXT) }
        entry(zip, "csv/clients.csv") { out ->
            val rows = dsl.selectFrom(CLIENTS).orderBy(CLIENTS.NAME).fetch().asSequence().map {
                listOf(it.name, it.currency, it.addressLine1, it.addressLine2, it.postalCode, it.city, it.region, it.countryCode, it.vatId, it.companyRegNo, it.peppolScheme, it.peppolId, if (it.isActive) "yes" else "no", it.notes)
            }
            Csv.write(ReportTable("clients", listOf("Name", "Currency", "Address", "Address 2", "Postal code", "City", "Region", "Country", "VAT ID", "Company number", "Peppol scheme", "Peppol ID", "Active", "Notes").map(text), rows), out); 0
        }
        entry(zip, "csv/contacts.csv") { out ->
            val rows = dsl.select(CLIENTS.NAME, CLIENT_CONTACTS.NAME, CLIENT_CONTACTS.TITLE, CLIENT_CONTACTS.EMAIL, CLIENT_CONTACTS.PHONE)
                .from(CLIENT_CONTACTS).join(CLIENTS).on(CLIENTS.ID.eq(CLIENT_CONTACTS.CLIENT_ID)).orderBy(CLIENTS.NAME, CLIENT_CONTACTS.NAME)
                .fetch().asSequence().map { it.intoList() }
            Csv.write(ReportTable("contacts", listOf("Client", "Name", "Title", "Email", "Phone").map(text), rows), out); 0
        }
        entry(zip, "csv/projects.csv") { out ->
            val rows = dsl.select(CLIENTS.NAME, PROJECTS.NAME, PROJECTS.CODE, PROJECTS.IS_BILLABLE, PROJECTS.BILL_BY, PROJECTS.BUDGET_BY, PROJECTS.STARTS_ON, PROJECTS.ENDS_ON, PROJECTS.IS_ACTIVE)
                .from(PROJECTS).join(CLIENTS).on(CLIENTS.ID.eq(PROJECTS.CLIENT_ID)).orderBy(CLIENTS.NAME, PROJECTS.NAME)
                .fetch().asSequence().map { r -> r.intoList().map { if (it is Boolean) (if (it) "yes" else "no") else it?.toString() } }
            Csv.write(ReportTable("projects", listOf("Client", "Project", "Code", "Billable", "Bill by", "Budget by", "Starts", "Ends", "Active").map(text), rows), out); 0
        }
        entry(zip, "csv/tasks.csv") { out ->
            val rows = dsl.selectFrom(TASKS).orderBy(TASKS.NAME).fetch().asSequence().map { listOf(it.name, if (it.defaultBillable) "yes" else "no", if (it.isActive) "yes" else "no") }
            Csv.write(ReportTable("tasks", listOf("Task", "Billable by default", "Active").map(text), rows), out); 0
        }
        entry(zip, "csv/people.csv") { out ->
            val rows = dsl.selectFrom(MEMBERSHIPS).orderBy(MEMBERSHIPS.NAME).fetch().asSequence().map { listOf(it.name, it.email, it.role, if (it.isActive) "yes" else "no") }
            Csv.write(ReportTable("people", listOf("Name", "Email", "Role", "Active").map(text), rows), out); 0
        }
        entry(zip, "csv/invoices.csv") { out ->
            val rows = dsl.select(INVOICES.NUMBER, CLIENTS.NAME, INVOICES.STATE, INVOICES.ISSUE_DATE, INVOICES.DUE_DATE, INVOICES.CURRENCY, INVOICES.SUBTOTAL_MINOR, INVOICES.TAX1_MINOR.plus(INVOICES.TAX2_MINOR), INVOICES.TOTAL_MINOR, INVOICES.PAID_MINOR, INVOICES.SUBJECT)
                .from(INVOICES).join(CLIENTS).on(CLIENTS.ID.eq(INVOICES.CLIENT_ID)).orderBy(INVOICES.ISSUE_DATE, INVOICES.NUMBER)
                .fetch().asSequence().map { r ->
                    val c = r.value6()
                    listOf(r.value1(), r.value2(), r.value3(), r.value4(), r.value5(), c,
                        Money.fromMinor(r.value7(), c), Money.fromMinor(r.value8(), c), Money.fromMinor(r.value9(), c), Money.fromMinor(r.value10(), c), r.value11())
                }
            val columns = listOf(text("Number"), text("Client"), text("State"), Column("Issued", ColumnType.DATE), Column("Due", ColumnType.DATE), text("Currency"),
                Column("Subtotal", ColumnType.MONEY), Column("Tax", ColumnType.MONEY), Column("Total", ColumnType.MONEY), Column("Paid", ColumnType.MONEY), text("Subject"))
            Csv.write(ReportTable("invoices", columns, rows), out); 0
        }
    }

    /** Every issued invoice as the PDF its client received (drafts have no number yet). */
    private fun writeInvoicePdfs(zip: ZipOutputStream) {
        dsl.selectFrom(INVOICES).where(INVOICES.STATE.ne("draft")).orderBy(INVOICES.ISSUE_DATE, INVOICES.ID).fetch().forEach { inv ->
            try {
                val bytes = pdf.pdf(inv)
                entry(zip, "invoices/${pdf.filename(inv)}") { out -> out.write(bytes); 0 }
            } catch (e: Exception) {
                // One broken invoice must not cost the whole export; its data is in the JSON.
                log.warn("Invoice {} could not be rendered for the export: {}", inv.id, e.message)
            }
        }
    }

    private fun writeFiles(zip: ZipOutputStream): List<ExportedFile> =
        dsl.selectFrom(FILES).orderBy(FILES.ID).fetch().mapNotNull { f ->
            val path = "files/${f.id}/${f.filename.replace(Regex("[/\\\\]"), "_")}"
            try {
                storage.open(f.storageKey).use { input -> entry(zip, path) { out -> input.copyTo(out); 0 } }
                ExportedFile(f.id, path, f.size, f.sha256)
            } catch (e: java.io.IOException) {
                log.warn("File {} is missing from storage and was left out of the export", f.id)
                null
            }
        }

    private fun readme(m: ExportManifest) = buildString {
        appendLine("Honest Robin Time: export of ${m.accountName}")
        appendLine("Exported ${m.exportedAt} from Honest Robin ${m.appVersion}.")
        appendLine()
        appendLine("data/       Everything in the account, one JSON file per table. This is the complete")
        appendLine("            record; docs/export-format.md in the source code describes every field.")
        appendLine("csv/        The same data as spreadsheets, for reading and for other tools.")
        appendLine("invoices/   Every issued invoice as a PDF.")
        appendLine("files/      Receipts and other uploaded files.")
        appendLine("manifest.json  Row counts and SHA-256 checksums of every data file.")
        appendLine()
        appendLine("To move this account to another Honest Robin instance, cloud or self-hosted, sign in")
        appendLine("there and choose Settings > Account > Import an export. You become its admin; invite")
        appendLine("everyone else again from Team. Their history comes along; passwords don't.")
        appendLine()
        appendLine("Not included:")
        m.excluded.values.distinct().forEach { appendLine("- $it") }
    }
}

/** Keeps a wrapped writer from closing the zip stream underneath it. */
class NonClosing(out: OutputStream) : FilterOutputStream(out) {
    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)

    override fun close() = flush()
}
