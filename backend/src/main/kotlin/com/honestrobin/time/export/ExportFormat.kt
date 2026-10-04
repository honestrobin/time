// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.export

import tools.jackson.core.JsonGenerator
import tools.jackson.core.util.MinimalPrettyPrinter
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import com.honestrobin.time.db.Tables.ACCOUNTING_SYNC_ITEMS
import com.honestrobin.time.db.Tables.ACCOUNTS
import com.honestrobin.time.db.Tables.ACCOUNT_EXPORTS
import com.honestrobin.time.db.Tables.ACCOUNT_MILESTONES
import com.honestrobin.time.db.Tables.API_TOKENS
import com.honestrobin.time.db.Tables.ARCHIVED_DOCUMENTS
import com.honestrobin.time.db.Tables.BILLING_EVENTS
import com.honestrobin.time.db.Tables.AUDIT_LOG
import com.honestrobin.time.db.Tables.BUDGET_ALERTS
import com.honestrobin.time.db.Tables.CLIENTS
import com.honestrobin.time.db.Tables.CLIENT_CONTACTS
import com.honestrobin.time.db.Tables.EINVOICE_TRANSMISSIONS
import com.honestrobin.time.db.Tables.EXPENSES
import com.honestrobin.time.db.Tables.EXPENSE_CATEGORIES
import com.honestrobin.time.db.Tables.EXTERNAL_LINKS
import com.honestrobin.time.db.Tables.FILES
import com.honestrobin.time.db.Tables.IMPORT_FILES
import com.honestrobin.time.db.Tables.IMPORT_ISSUES
import com.honestrobin.time.db.Tables.IMPORT_JOBS
import com.honestrobin.time.db.Tables.INTEGRATIONS
import com.honestrobin.time.db.Tables.INVOICES
import com.honestrobin.time.db.Tables.INVOICE_EXPENSE_LINKS
import com.honestrobin.time.db.Tables.INVOICE_LINES
import com.honestrobin.time.db.Tables.INVOICE_SEQUENCES
import com.honestrobin.time.db.Tables.INVOICE_TIME_LINKS
import com.honestrobin.time.db.Tables.LOGIN_TOKENS
import com.honestrobin.time.db.Tables.MEMBERSHIPS
import com.honestrobin.time.db.Tables.PAYMENTS
import com.honestrobin.time.db.Tables.PROJECTS
import com.honestrobin.time.db.Tables.PROJECT_MEMBERS
import com.honestrobin.time.db.Tables.PROJECT_TASKS
import com.honestrobin.time.db.Tables.SUBSCRIPTIONS
import com.honestrobin.time.db.Tables.TASKS
import com.honestrobin.time.db.Tables.TEAMS
import com.honestrobin.time.db.Tables.TEAM_MEMBERSHIPS
import com.honestrobin.time.db.Tables.TIMESHEET_ROWS
import com.honestrobin.time.db.Tables.TIMESHEET_SUBMISSIONS
import com.honestrobin.time.db.Tables.TIME_ENTRIES
import com.honestrobin.time.db.Tables.USERS
import com.honestrobin.time.db.Tables.USER_RECOVERY_CODES
import com.honestrobin.time.db.Tables.RATE_LIMIT_EVENTS
import com.honestrobin.time.db.Tables.DEVICE_AUTHORIZATIONS
import com.honestrobin.time.db.Tables.USER_SESSIONS
import org.jooq.Condition
import org.jooq.Field
import org.jooq.JSONB
import org.jooq.Table
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

/** One table in the export: which rows belong to the account, and which columns stay behind. */
class ExportTable(val table: Table<*>, val omit: Set<String> = emptySet(), val rows: (UUID) -> Condition) {
    val name: String get() = table.name
    val fields: List<Field<*>> get() = table.fields().filter { it.name !in omit }
}

/**
 * The account export (spec §13): a zip with one JSON file per table, lossless and documented in
 * docs/export-format.md, plus CSVs, invoice PDFs and receipt files for people. The same zip
 * imports into either edition.
 */
object ExportFormat {
    const val FORMAT = "honestrobin-export"
    const val VERSION = 1

    private fun byAccount(t: Table<*>): (UUID) -> Condition = { id -> t.field("account_id", UUID::class.java)!!.eq(id) }

    /** In import order: every table comes after the tables it refers to. */
    val TABLES: List<ExportTable> = listOf(
        // Lifecycle (lapsed, pending deletion) belongs to the instance, not to the data.
        ExportTable(ACCOUNTS, omit = setOf("status", "lapsed_at", "deletion_requested_at")) { ACCOUNTS.ID.eq(it) },
        // People, without anything that signs them in: passwords stay on the old instance.
        ExportTable(USERS, omit = setOf("password_hash", "is_instance_admin", "last_login_at", "totp_secret_encrypted", "totp_pending_encrypted", "totp_enabled_at", "totp_last_step")) {
            USERS.ID.`in`(DSL.select(MEMBERSHIPS.USER_ID).from(MEMBERSHIPS).where(MEMBERSHIPS.ACCOUNT_ID.eq(it)))
        },
        ExportTable(MEMBERSHIPS, rows = byAccount(MEMBERSHIPS)),
        ExportTable(TEAMS, rows = byAccount(TEAMS)),
        ExportTable(TEAM_MEMBERSHIPS, rows = byAccount(TEAM_MEMBERSHIPS)),
        ExportTable(CLIENTS, rows = byAccount(CLIENTS)),
        ExportTable(CLIENT_CONTACTS, rows = byAccount(CLIENT_CONTACTS)),
        ExportTable(TASKS, rows = byAccount(TASKS)),
        ExportTable(PROJECTS, rows = byAccount(PROJECTS)),
        ExportTable(PROJECT_TASKS, rows = byAccount(PROJECT_TASKS)),
        ExportTable(PROJECT_MEMBERS, rows = byAccount(PROJECT_MEMBERS)),
        ExportTable(FILES, rows = byAccount(FILES)),
        ExportTable(EXPENSE_CATEGORIES, rows = byAccount(EXPENSE_CATEGORIES)),
        ExportTable(INVOICE_SEQUENCES, rows = byAccount(INVOICE_SEQUENCES)),
        ExportTable(INVOICES, rows = byAccount(INVOICES)),
        ExportTable(INVOICE_LINES, rows = byAccount(INVOICE_LINES)),
        ExportTable(TIME_ENTRIES, rows = byAccount(TIME_ENTRIES)),
        ExportTable(TIMESHEET_ROWS, rows = byAccount(TIMESHEET_ROWS)),
        ExportTable(TIMESHEET_SUBMISSIONS, rows = byAccount(TIMESHEET_SUBMISSIONS)),
        ExportTable(EXPENSES, rows = byAccount(EXPENSES)),
        ExportTable(INVOICE_TIME_LINKS, rows = byAccount(INVOICE_TIME_LINKS)),
        ExportTable(INVOICE_EXPENSE_LINKS, rows = byAccount(INVOICE_EXPENSE_LINKS)),
        ExportTable(PAYMENTS, rows = byAccount(PAYMENTS)),
        ExportTable(EINVOICE_TRANSMISSIONS, rows = byAccount(EINVOICE_TRANSMISSIONS)),
        ExportTable(ARCHIVED_DOCUMENTS, rows = byAccount(ARCHIVED_DOCUMENTS)),
        ExportTable(EXTERNAL_LINKS, rows = byAccount(EXTERNAL_LINKS)),
        ExportTable(BUDGET_ALERTS, rows = byAccount(BUDGET_ALERTS)),
        ExportTable(ACCOUNT_MILESTONES, rows = byAccount(ACCOUNT_MILESTONES)),
        ExportTable(AUDIT_LOG) { AUDIT_LOG.ACCOUNT_ID.eq(it) },
    )

    /** Tables that are left out on purpose, and why (also listed in the export's README). */
    val EXCLUDED: Map<Table<*>, String> = mapOf(
        API_TOKENS to "API tokens are secrets; create new ones after importing.",
        INTEGRATIONS to "Connections (Stripe and others) are encrypted with this instance's key; connect them again after importing.",
        IMPORT_JOBS to "Import runs are a log of this instance's work, not account data.",
        IMPORT_ISSUES to "Import runs are a log of this instance's work, not account data.",
        IMPORT_FILES to "Import runs are a log of this instance's work, not account data.",
        ACCOUNT_EXPORTS to "Earlier exports.",
        USER_SESSIONS to "Sign-in sessions.",
        LOGIN_TOKENS to "Sign-in links.",
        USER_RECOVERY_CODES to "Two-factor sign-in is set up again on the new instance.",
        RATE_LIMIT_EVENTS to "Rate-limit counters of that instance.",
        DEVICE_AUTHORIZATIONS to "Sign-ins of devices such as the browser extension; sign them in again.",
        ACCOUNTING_SYNC_ITEMS to "The queue of pushes to QuickBooks or Xero; connect again after importing.",
        SUBSCRIPTIONS to "An Honest Robin Cloud subscription belongs to that instance; subscribe again where you import.",
        BILLING_EVENTS to "An Honest Robin Cloud subscription belongs to that instance; subscribe again where you import.",
    )

    fun table(name: String): ExportTable? = TABLES.firstOrNull { it.name == name }
}

/** JSON values for database values, both ways, so an export imports back exactly. */
object ExportCodec {
    fun write(g: JsonGenerator, v: Any?) {
        when (v) {
            null -> g.writeNull()
            is String -> g.writeString(v)
            is UUID -> g.writeString(v.toString())
            is Boolean -> g.writeBoolean(v)
            is Short -> g.writeNumber(v)
            is Int -> g.writeNumber(v)
            is Long -> g.writeNumber(v)
            is BigDecimal -> g.writeNumber(v)
            is LocalDate, is LocalTime, is Instant, is OffsetDateTime -> g.writeString(v.toString())
            // Postgres hands jsonb back in a normal form, so it is embedded as is.
            is JSONB -> g.writeRawValue(v.data())
            is ByteArray -> g.writeString(Base64.getEncoder().encodeToString(v))
            is Array<*> -> {
                g.writeStartArray()
                v.forEach { write(g, it) }
                g.writeEndArray()
            }
            else -> error("Can't export a ${v::class.java.name}")
        }
    }

    fun read(node: JsonNode?, type: Class<*>, json: ObjectMapper): Any? {
        if (node == null || node.isNull) return null
        return when {
            type == String::class.java -> node.asText()
            type == UUID::class.java -> UUID.fromString(node.asText())
            type == java.lang.Boolean::class.java || type == Boolean::class.java -> node.asBoolean()
            type == java.lang.Short::class.java -> node.shortValue()
            type == java.lang.Integer::class.java -> node.intValue()
            type == java.lang.Long::class.java -> node.longValue()
            type == BigDecimal::class.java -> node.decimalValue()
            type == LocalDate::class.java -> LocalDate.parse(node.asText())
            type == LocalTime::class.java -> LocalTime.parse(node.asText())
            type == Instant::class.java -> Instant.parse(node.asText())
            type == OffsetDateTime::class.java -> OffsetDateTime.parse(node.asText())
            type == JSONB::class.java -> JSONB.valueOf(json.writeValueAsString(node))
            type == ByteArray::class.java -> Base64.getDecoder().decode(node.asText())
            type.isArray -> {
                val component = type.componentType
                val array = java.lang.reflect.Array.newInstance(component, node.size())
                node.forEachIndexed { i, x -> java.lang.reflect.Array.set(array, i, read(x, component, json)) }
                array
            }
            else -> error("Can't import a ${type.name}")
        }
    }
}

/** One row per line inside the top-level array: readable, diffable and still plain JSON. */
class RowPerLinePrinter : MinimalPrettyPrinter() {
    private var depth = 0

    override fun writeStartArray(g: JsonGenerator) {
        g.writeRaw(if (depth++ == 0) "[\n" else "[")
    }

    override fun writeEndArray(g: JsonGenerator, nrOfValues: Int) {
        g.writeRaw(if (--depth == 0) (if (nrOfValues > 0) "\n]\n" else "]\n") else "]")
    }

    override fun writeArrayValueSeparator(g: JsonGenerator) {
        g.writeRaw(if (depth == 1) ",\n" else ",")
    }

    override fun writeStartObject(g: JsonGenerator) {
        depth++
        g.writeRaw('{')
    }

    override fun writeEndObject(g: JsonGenerator, nrOfEntries: Int) {
        depth--
        g.writeRaw('}')
    }
}
