// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.reports

import com.honestrobin.time.accounts.AccountSettingsRepository
import com.honestrobin.time.db.Tables.CLIENTS
import com.honestrobin.time.db.Tables.EXPENSES
import com.honestrobin.time.db.Tables.EXPENSE_CATEGORIES
import com.honestrobin.time.db.Tables.MEMBERSHIPS
import com.honestrobin.time.db.Tables.PROJECTS
import com.honestrobin.time.db.Tables.TASKS
import com.honestrobin.time.db.Tables.TIME_ENTRIES
import com.honestrobin.time.expenses.ExpenseService
import com.honestrobin.time.platform.Money
import com.honestrobin.time.platform.security.Member
import com.honestrobin.time.platform.web.ValidationException
import com.honestrobin.time.time.TimeEntryService
import org.dhatim.fastexcel.Workbook
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.context.MessageSource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.OutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.Locale

enum class ColumnType { TEXT, DATE, HOURS, MONEY, NUMBER, PERCENT }

/** HOURS cells hold seconds; MONEY cells hold decimal amounts (already scaled to their currency). */
data class Column(val header: String, val type: ColumnType)

/** [totalRow]: the last row sums the others, and is set in bold in a spreadsheet. */
class ReportTable(val sheet: String, val columns: List<Column>, val rows: Sequence<List<Any?>>, val totalRow: Boolean = false)

/** What to export: a report kind with the same parameters as its JSON endpoint. */
data class ExportRequest(
    val kind: String,
    val format: String,
    val groupBy: String?,
    val filter: ReportFilter,
    val includeArchived: Boolean = false,
) {
    fun validate() {
        if (kind !in KINDS) throw ValidationException("kind", "Unknown report: $kind")
        if (format !in FORMATS) throw ValidationException("format", "Export as csv or xlsx")
        filter.validate()
        groupBy?.let {
            val allowed = if (kind == "expenses") setOf("category", "client", "project", "person") else setOf("client", "project", "task", "person")
            if (it !in allowed) throw ValidationException("group_by", "Can't group this report by $it")
        }
    }

    val contentType get() = if (format == "csv") "text/csv; charset=utf-8" else "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    /** e.g. time-by-project-2026-09-01-to-2026-09-30.csv */
    val filename: String get() {
        val name = when (kind) {
            "time" -> "time-by-${groupBy ?: "project"}"
            "expenses" -> "expenses-by-${groupBy ?: "category"}"
            else -> kind.replace('_', '-')
        }
        val range = when {
            filter.from != null && filter.to != null -> "-${filter.from}-to-${filter.to}"
            filter.from != null -> "-from-${filter.from}"
            filter.to != null -> "-to-${filter.to}"
            else -> ""
        }
        return "$name$range.$format"
    }

    companion object {
        val KINDS = setOf("time", "detailed", "uninvoiced", "budget", "expenses", "expense_items")
        val FORMATS = setOf("csv", "xlsx")
    }
}

/**
 * CSV and XLSX exports of every report (spec §12). Rows stream from the database to the response,
 * so a detailed export of a large account doesn't sit in memory. Rates and amounts are left out
 * for people who may not see them, as in the app.
 */
@Service
class ReportExportService(
    private val reports: ReportService,
    private val entries: TimeEntryService,
    private val expenses: ExpenseService,
    private val accounts: AccountSettingsRepository,
    private val dsl: DSLContext,
    private val messages: MessageSource,
) {

    @Transactional(readOnly = true)
    fun write(m: Member, req: ExportRequest, out: OutputStream) {
        req.validate()
        val locale = Locale.forLanguageTag(accounts.get(m.accountId).locale)
        val labels = Labels(messages, locale)
        val table = when (req.kind) {
            "time" -> timeTable(m, req, labels)
            "detailed" -> detailedTable(m, req, labels)
            "uninvoiced" -> uninvoicedTable(m, req, labels)
            "budget" -> budgetTable(m, req, labels)
            "expenses" -> expensesTable(m, req, labels)
            else -> expenseItemsTable(m, req, labels)
        }
        if (req.format == "csv") Csv.write(table, out) else Xlsx.write(table, out, labels)
    }

    private fun timeTable(m: Member, req: ExportRequest, l: Labels): ReportTable {
        val groupBy = req.groupBy ?: "project"
        val r = reports.time(m, groupBy, req.filter)
        val currencies = r.amounts.map { it.currency }
        val rates = m.canSeeRates
        val columns = buildList {
            add(Column(l("report.col.$groupBy"), ColumnType.TEXT))
            if (groupBy == "project") add(Column(l("report.col.client"), ColumnType.TEXT))
            add(Column(l("report.col.hours"), ColumnType.HOURS))
            add(Column(l("report.col.billableHours"), ColumnType.HOURS))
            add(Column(l("report.col.billablePercent"), ColumnType.PERCENT))
            if (rates) {
                currencies.forEach {
                    add(Column(l("report.col.billableAmountIn", it), ColumnType.MONEY))
                    add(Column(l("report.col.uninvoicedAmountIn", it), ColumnType.MONEY))
                }
                add(Column(l("report.col.costAmountIn", r.costCurrency), ColumnType.MONEY))
            }
        }
        fun line(name: String, client: String?, seconds: Long, billable: Long, amounts: List<CurrencyAmount>, cost: Long) = buildList {
            add(name)
            if (groupBy == "project") add(client)
            add(seconds)
            add(billable)
            add(percent(billable, seconds))
            if (rates) {
                currencies.forEach { c ->
                    val a = amounts.firstOrNull { it.currency == c }
                    add(Money.fromMinor(a?.billableAmount ?: 0, c))
                    add(Money.fromMinor(a?.uninvoicedAmount ?: 0, c))
                }
                add(Money.fromMinor(cost, r.costCurrency))
            }
        }
        val rows = r.rows.map { line(it.name, it.clientName, it.seconds, it.billableSeconds, it.amounts, it.costAmount) } +
            listOf(line(l("report.total"), null, r.seconds, r.billableSeconds, r.amounts, r.costAmount))
        return ReportTable(l("report.sheet.time"), columns, rows.asSequence(), totalRow = true)
    }

    private fun detailedTable(m: Member, req: ExportRequest, l: Labels): ReportTable {
        val s = accounts.get(m.accountId)
        val fields = reports.fields(s)
        val rates = m.canSeeRates
        val columns = buildList {
            add(Column(l("report.col.date"), ColumnType.DATE))
            add(Column(l("report.col.client"), ColumnType.TEXT))
            add(Column(l("report.col.project"), ColumnType.TEXT))
            add(Column(l("report.col.projectCode"), ColumnType.TEXT))
            add(Column(l("report.col.task"), ColumnType.TEXT))
            add(Column(l("report.col.notes"), ColumnType.TEXT))
            add(Column(l("report.col.hours"), ColumnType.HOURS))
            add(Column(l("report.col.roundedHours"), ColumnType.HOURS))
            add(Column(l("report.col.billable"), ColumnType.TEXT))
            add(Column(l("report.col.invoiced"), ColumnType.TEXT))
            add(Column(l("report.col.approval"), ColumnType.TEXT))
            add(Column(l("report.col.person"), ColumnType.TEXT))
            if (rates) {
                add(Column(l("report.col.currency"), ColumnType.TEXT))
                add(Column(l("report.col.billableRate"), ColumnType.MONEY))
                add(Column(l("report.col.billableAmount"), ColumnType.MONEY))
                add(Column(l("report.col.costRateIn", s.defaultCurrency), ColumnType.MONEY))
                add(Column(l("report.col.costAmountIn", s.defaultCurrency), ColumnType.MONEY))
            }
            add(Column(l("report.col.externalUrl"), ColumnType.TEXT))
        }
        val cursor = dsl.select(
            TIME_ENTRIES.SPENT_DATE, CLIENTS.NAME, PROJECTS.NAME, PROJECTS.CODE, TASKS.NAME, TIME_ENTRIES.NOTES,
            fields.seconds, fields.rounded, TIME_ENTRIES.BILLABLE, TIME_ENTRIES.INVOICE_ID, TIME_ENTRIES.APPROVAL_STATE, MEMBERSHIPS.NAME,
            CLIENTS.CURRENCY, TIME_ENTRIES.BILLABLE_RATE_SNAPSHOT, fields.amount, TIME_ENTRIES.COST_RATE_SNAPSHOT, fields.cost, TIME_ENTRIES.EXTERNAL_URL,
        )
            .from(TIME_ENTRIES)
            .join(PROJECTS).on(PROJECTS.ID.eq(TIME_ENTRIES.PROJECT_ID))
            .join(CLIENTS).on(CLIENTS.ID.eq(PROJECTS.CLIENT_ID))
            .join(TASKS).on(TASKS.ID.eq(TIME_ENTRIES.TASK_ID))
            .join(MEMBERSHIPS).on(MEMBERSHIPS.ID.eq(TIME_ENTRIES.MEMBERSHIP_ID))
            .where(entries.visibility(m)).and(reports.timeCondition(req.filter))
            .orderBy(TIME_ENTRIES.SPENT_DATE, MEMBERSHIPS.NAME, TIME_ENTRIES.ID)
            .fetchSize(1000).fetchLazy()
        val rows = cursor.asSequence().map { r ->
            val currency = r.value13()
            buildList {
                add(r.value1()); add(r.value2()); add(r.value3()); add(r.value4()); add(r.value5()); add(r.value6())
                add(r.value7().toLong()); add(r.value8().toLong())
                add(l.yesNo(r.value9())); add(l.yesNo(r.value10() != null)); add(l("report.approval.${r.value11()}")); add(r.value12())
                if (rates) {
                    add(currency)
                    add(Money.fromMinor(r.value14(), currency)); add(Money.fromMinor(r.value15(), currency))
                    add(Money.fromMinor(r.value16(), s.defaultCurrency)); add(Money.fromMinor(r.value17(), s.defaultCurrency))
                }
                add(r.value18())
            }
        }
        return ReportTable(l("report.sheet.detailed"), columns, rows)
    }

    private fun uninvoicedTable(m: Member, req: ExportRequest, l: Labels): ReportTable {
        val r = reports.uninvoiced(m, req.filter)
        val rates = m.canSeeRates
        val columns = buildList {
            add(Column(l("report.col.client"), ColumnType.TEXT))
            add(Column(l("report.col.project"), ColumnType.TEXT))
            add(Column(l("report.col.hours"), ColumnType.HOURS))
            if (rates) {
                add(Column(l("report.col.currency"), ColumnType.TEXT))
                add(Column(l("report.col.amount"), ColumnType.MONEY))
                add(Column(l("report.col.expenses"), ColumnType.MONEY))
            }
            add(Column(l("report.col.oldest"), ColumnType.DATE))
        }
        val rows = r.clients.flatMap { c ->
            c.projects.map { p ->
                buildList {
                    add(c.client.name); add(p.project.name); add(p.seconds)
                    if (rates) { add(c.currency); add(Money.fromMinor(p.amount, c.currency)); add(Money.fromMinor(p.expenseAmount, c.currency)) }
                    add(p.oldestDate)
                }
            }
        }
        return ReportTable(l("report.sheet.uninvoiced"), columns, rows.asSequence())
    }

    private fun budgetTable(m: Member, req: ExportRequest, l: Labels): ReportTable {
        val r = reports.budget(m, req.filter.clientIds, req.includeArchived)
        val rates = m.canSeeRates
        val columns = buildList {
            add(Column(l("report.col.client"), ColumnType.TEXT))
            add(Column(l("report.col.project"), ColumnType.TEXT))
            add(Column(l("report.col.budgetBy"), ColumnType.TEXT))
            add(Column(l("report.col.periodStart"), ColumnType.DATE))
            add(Column(l("report.col.budgetHours"), ColumnType.HOURS))
            add(Column(l("report.col.spentHours"), ColumnType.HOURS))
            add(Column(l("report.col.remainingHours"), ColumnType.HOURS))
            if (rates) {
                add(Column(l("report.col.currency"), ColumnType.TEXT))
                add(Column(l("report.col.budgetAmount"), ColumnType.MONEY))
                add(Column(l("report.col.spentAmount"), ColumnType.MONEY))
                add(Column(l("report.col.remainingAmount"), ColumnType.MONEY))
            }
            add(Column(l("report.col.percentUsed"), ColumnType.PERCENT))
            add(Column(l("report.col.overBudget"), ColumnType.TEXT))
        }
        val rows = r.rows
            // Without rates, fee budgets would show nothing but a percentage of an unknown amount.
            .filter { rates || it.budget.measure == "time" }
            .map { row ->
                val b = row.budget
                buildList {
                    add(row.client.name); add(row.project.name); add(l("report.budgetBy.${b.budgetBy}")); add(b.periodStart)
                    add(b.budgetSeconds); add(b.spentSeconds); add(b.remainingSeconds)
                    if (rates) {
                        add(b.currency)
                        add(b.budgetAmount?.let { Money.fromMinor(it, b.currency) })
                        add(Money.fromMinor(b.spentAmount, b.currency))
                        add(b.remainingAmount?.let { Money.fromMinor(it, b.currency) })
                    }
                    add(b.percentUsed); add(l.yesNo(b.isOverBudget))
                }
            }
        return ReportTable(l("report.sheet.budget"), columns, rows.asSequence())
    }

    private fun expensesTable(m: Member, req: ExportRequest, l: Labels): ReportTable {
        val groupBy = req.groupBy ?: "category"
        val r = reports.expenses(m, groupBy, req.filter)
        val currencies = r.amounts.map { it.currency }
        val columns = buildList {
            add(Column(l("report.col.$groupBy"), ColumnType.TEXT))
            if (groupBy == "project") add(Column(l("report.col.client"), ColumnType.TEXT))
            add(Column(l("report.col.count"), ColumnType.NUMBER))
            currencies.forEach {
                add(Column(l("report.col.amountIn", it), ColumnType.MONEY))
                add(Column(l("report.col.billableIn", it), ColumnType.MONEY))
                add(Column(l("report.col.uninvoicedIn", it), ColumnType.MONEY))
            }
        }
        fun line(name: String, client: String?, count: Int, amounts: List<ExpenseAmount>) = buildList {
            add(name)
            if (groupBy == "project") add(client)
            add(count)
            currencies.forEach { c ->
                val a = amounts.firstOrNull { it.currency == c }
                add(Money.fromMinor(a?.amount ?: 0, c)); add(Money.fromMinor(a?.billableAmount ?: 0, c)); add(Money.fromMinor(a?.uninvoicedAmount ?: 0, c))
            }
        }
        val rows = r.rows.map { line(it.name, it.clientName, it.count, it.amounts) } + listOf(line(l("report.total"), null, r.count, r.amounts))
        return ReportTable(l("report.sheet.expenses"), columns, rows.asSequence(), totalRow = true)
    }

    private fun expenseItemsTable(m: Member, req: ExportRequest, l: Labels): ReportTable {
        val columns = listOf(
            Column(l("report.col.date"), ColumnType.DATE),
            Column(l("report.col.client"), ColumnType.TEXT),
            Column(l("report.col.project"), ColumnType.TEXT),
            Column(l("report.col.category"), ColumnType.TEXT),
            Column(l("report.col.person"), ColumnType.TEXT),
            Column(l("report.col.notes"), ColumnType.TEXT),
            Column(l("report.col.units"), ColumnType.NUMBER),
            Column(l("report.col.currency"), ColumnType.TEXT),
            Column(l("report.col.amount"), ColumnType.MONEY),
            Column(l("report.col.billable"), ColumnType.TEXT),
            Column(l("report.col.invoiced"), ColumnType.TEXT),
            Column(l("report.col.approval"), ColumnType.TEXT),
        )
        val cursor = dsl.select(
            EXPENSES.SPENT_DATE, CLIENTS.NAME, PROJECTS.NAME, EXPENSE_CATEGORIES.NAME, MEMBERSHIPS.NAME, EXPENSES.NOTES,
            EXPENSES.UNITS, EXPENSES.CURRENCY, EXPENSES.AMOUNT_MINOR, EXPENSES.BILLABLE, EXPENSES.INVOICE_ID, EXPENSES.APPROVAL_STATE,
        )
            .from(EXPENSES)
            .join(PROJECTS).on(PROJECTS.ID.eq(EXPENSES.PROJECT_ID))
            .join(CLIENTS).on(CLIENTS.ID.eq(PROJECTS.CLIENT_ID))
            .join(EXPENSE_CATEGORIES).on(EXPENSE_CATEGORIES.ID.eq(EXPENSES.CATEGORY_ID))
            .join(MEMBERSHIPS).on(MEMBERSHIPS.ID.eq(EXPENSES.MEMBERSHIP_ID))
            .where(expenses.visibility(m)).and(reports.expenseCondition(req.filter))
            .orderBy(EXPENSES.SPENT_DATE, MEMBERSHIPS.NAME, EXPENSES.ID)
            .fetchSize(1000).fetchLazy()
        val rows = cursor.asSequence().map { r ->
            listOf(
                r.value1(), r.value2(), r.value3(), r.value4(), r.value5(), r.value6(), r.value7(), r.value8(),
                Money.fromMinor(r.value9(), r.value8()), l.yesNo(r.value10()), l.yesNo(r.value11() != null), l("report.approval.${r.value12()}"),
            )
        }
        return ReportTable(l("report.sheet.expense_items"), columns, rows)
    }

    private fun percent(part: Long, whole: Long): BigDecimal? =
        if (whole == 0L) null else BigDecimal(part * 100).divide(BigDecimal(whole), 1, RoundingMode.HALF_UP)
}

class Labels(private val messages: MessageSource, private val locale: Locale) {
    operator fun invoke(key: String, vararg args: Any?): String = messages.getMessage(key, args, key, locale) ?: key
    fun yesNo(b: Boolean) = invoke(if (b) "report.yes" else "report.no")
}

private fun hours(seconds: Long): BigDecimal = BigDecimal(seconds).divide(BigDecimal(3600), 2, RoundingMode.HALF_UP)

/** RFC 4180 CSV in UTF-8 with a byte order mark, so Excel opens accented names correctly. */
object Csv {
    fun write(table: ReportTable, out: OutputStream) {
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write("﻿")
        w.write(table.columns.joinToString(",") { escape(it.header) })
        w.write("\r\n")
        for (row in table.rows) {
            w.write(row.indices.joinToString(",") { i -> cell(table.columns[i].type, row[i]) })
            w.write("\r\n")
        }
        w.flush()
    }

    fun cell(type: ColumnType, v: Any?): String = when {
        v == null -> ""
        type == ColumnType.HOURS -> hours((v as Number).toLong()).toPlainString()
        v is BigDecimal -> v.toPlainString()
        v is Number || v is LocalDate -> v.toString()
        else -> escape(guard(v.toString()))
    }

    /**
     * Text a spreadsheet would run as a formula gets a leading apostrophe (OWASP "CSV injection"):
     * notes and names are typed by people, and an export may be opened by someone else.
     */
    fun guard(s: String): String = if (s.isNotEmpty() && s[0] in "=+-@\t\r") "'$s" else s

    fun escape(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' } || s != s.trim()) "\"" + s.replace("\"", "\"\"") + "\"" else s
}

/** XLSX with typed cells: numbers stay numbers and dates stay dates, so sums and filters work. */
object Xlsx {
    /** Excel's row limit, less the header row. */
    const val MAX_ROWS = 1_048_575

    fun write(table: ReportTable, out: OutputStream, labels: Labels) {
        val wb = Workbook(out, "Honest Robin", null)
        val ws = wb.newWorksheet(table.sheet.take(31).replace(Regex("[\\[\\]:*?/\\\\]"), " "))
        table.columns.forEachIndexed { c, col ->
            ws.value(0, c, col.header)
            ws.style(0, c).bold().fillColor("EEF0F2").set()
            ws.width(c, when (col.type) { ColumnType.TEXT -> 24.0; ColumnType.DATE -> 12.0; else -> 14.0 })
        }
        // Panes and widths are written ahead of the rows, so set them before the first flush.
        ws.freezePane(0, 1)
        var r = 0
        val rows = table.rows.iterator()
        while (rows.hasNext()) {
            val row = rows.next()
            if (r == MAX_ROWS - 1) {
                ws.value(r + 1, 0, labels("report.truncated", MAX_ROWS - 1))
                break
            }
            r++
            val bold = table.totalRow && !rows.hasNext()
            row.forEachIndexed { c, v ->
                if (v == null) return@forEachIndexed
                val format = when (table.columns[c].type) {
                    ColumnType.DATE -> "yyyy-mm-dd".also { ws.value(r, c, v as LocalDate) }
                    ColumnType.HOURS -> "0.00".also { ws.value(r, c, hours((v as Number).toLong())) }
                    ColumnType.MONEY -> (v as BigDecimal).let { d ->
                        ws.value(r, c, d)
                        if (d.scale() <= 0) "#,##0" else "#,##0." + "0".repeat(d.scale())
                    }
                    ColumnType.PERCENT -> "0.0".also { ws.value(r, c, v as Number) }
                    ColumnType.NUMBER -> null.also { ws.value(r, c, v as Number) }
                    ColumnType.TEXT -> null.also { ws.value(r, c, v.toString()) }
                }
                if (format != null || bold) {
                    val style = ws.style(r, c)
                    format?.let { style.format(it) }
                    if (bold) style.bold()
                    style.set()
                }
            }
            if (r % 1000 == 0) ws.flush()
        }
        wb.finish()
    }
}
