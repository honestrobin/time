// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.importers.csv

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import java.io.StringReader
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** A CSV file read into memory: its header and rows, whatever its encoding and delimiter. */
class CsvTable(val columns: List<String>, val rows: List<List<String>>) {
    fun value(row: List<String>, column: String?): String? {
        if (column == null) return null
        val i = columns.indexOf(column)
        return row.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() }
    }

    companion object {
        const val MAX_ROWS = 300_000

        /**
         * Reads Harvest's exports and what spreadsheet apps save: UTF-8 (with or without a byte
         * order mark), UTF-16 from Excel's "Unicode text", or Windows-1252; comma, semicolon or tab.
         */
        fun read(bytes: ByteArray): CsvTable {
            val text = decode(bytes)
            val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: throw CsvException("The file is empty")
            val delimiter = listOf(',', ';', '\t').maxBy { d -> firstLine.count { it == d } }
            val format = CSVFormat.RFC4180.builder().setDelimiter(delimiter).setIgnoreEmptyLines(true).setTrim(false).get()
            CSVParser.parse(StringReader(text), format).use { parser ->
                val records = parser.iterator()
                if (!records.hasNext()) throw CsvException("The file is empty")
                val header = records.next().toList().map { it.trim() }
                if (header.size < 2 || header.all { it.isEmpty() }) throw CsvException("The first row should name the columns")
                // Columns named the same twice get a number, so each can be chosen in the mapping.
                val seen = mutableMapOf<String, Int>()
                val columns = header.mapIndexed { i, h ->
                    val name = h.ifEmpty { "Column ${i + 1}" }
                    val n = seen.merge(name.lowercase(), 1, Int::plus)!!
                    if (n > 1) "$name ($n)" else name
                }
                val rows = ArrayList<List<String>>()
                for (r in records) {
                    if (rows.size >= MAX_ROWS) throw CsvException("The file has more than $MAX_ROWS rows; split it by date range")
                    val values = r.toList()
                    if (values.all { it.isBlank() }) continue
                    rows += values
                }
                return CsvTable(columns, rows)
            }
        }

        private fun decode(bytes: ByteArray): String {
            fun strict(charset: Charset, from: Int = 0): String? = try {
                charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, from, bytes.size - from)).toString()
            } catch (_: CharacterCodingException) {
                null
            }
            return when {
                bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
                bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
                else -> strict(Charsets.UTF_8) ?: String(bytes, Charset.forName("windows-1252"))
            }
        }
    }
}

class CsvException(message: String) : RuntimeException(message)

/** What a file holds: Harvest's detailed time report, or its clients, contacts or projects export. */
enum class CsvKind(val label: String) {
    TIME("Time entries (Harvest's detailed time report)"),
    CLIENTS("Clients"),
    CONTACTS("Client contacts"),
    PROJECTS("Projects"),
}

/** A field the import understands, and the column headers that usually hold it. */
data class CsvField(val name: String, val label: String, val required: Boolean, val synonyms: List<String>)

object CsvFields {
    private fun f(name: String, label: String, required: Boolean, vararg synonyms: String) = CsvField(name, label, required, synonyms.toList())

    val fields: Map<CsvKind, List<CsvField>> = mapOf(
        CsvKind.TIME to listOf(
            f("date", "Date", true, "Date", "Spent Date", "Day"),
            f("client", "Client", true, "Client", "Client Name", "Customer"),
            f("project", "Project", true, "Project", "Project Name"),
            f("project_code", "Project code", false, "Project Code", "Code"),
            f("task", "Task", true, "Task", "Task Name", "Activity"),
            f("notes", "Notes", false, "Notes", "Note", "Description", "Comments"),
            f("hours", "Hours", true, "Hours", "Duration", "Time", "Hours (decimal)"),
            f("first_name", "First name", false, "First Name", "First name", "Firstname"),
            f("last_name", "Last name", false, "Last Name", "Last name", "Lastname", "Surname"),
            // Not "Employee": Harvest's "Employee?" column says whether the person is an employee.
            f("person", "Person (full name)", false, "Person", "User", "Team Member", "Member", "Name"),
            f("email", "Email", false, "Email", "User Email", "Email Address"),
            f("billable", "Billable?", false, "Billable?", "Billable"),
            f("billable_rate", "Billable rate", false, "Billable Rate", "Hourly Rate", "Rate"),
            f("cost_rate", "Cost rate", false, "Cost Rate"),
            f("currency", "Currency", false, "Currency"),
            f("approved", "Approved?", false, "Approved?", "Approved", "Approval Status"),
            f("invoiced", "Invoiced?", false, "Invoiced?", "Invoiced", "Billed?", "Billed"),
            f("external_url", "Link (external reference)", false, "External Reference URL", "External URL", "URL"),
        ),
        CsvKind.CLIENTS to listOf(
            f("name", "Client name", true, "Client Name", "Client", "Name"),
            f("address", "Address", false, "Client Address", "Address"),
            f("currency", "Currency", false, "Client Currency", "Currency"),
        ),
        CsvKind.CONTACTS to listOf(
            f("client", "Client", true, "Client", "Client Name"),
            f("first_name", "First name", false, "First Name", "First name"),
            f("last_name", "Last name", false, "Last Name", "Last name"),
            f("title", "Title", false, "Title", "Job Title"),
            f("email", "Email", false, "Email", "Email Address"),
            f("phone", "Phone", false, "Office Phone", "Phone", "Mobile Phone", "Mobile"),
        ),
        CsvKind.PROJECTS to listOf(
            f("client", "Client", true, "Client", "Client Name"),
            f("project", "Project", true, "Project", "Project Name", "Name"),
            f("code", "Project code", false, "Project Code", "Code"),
            f("billable", "Billable?", false, "Billable?", "Billable", "Is Billable"),
            f("notes", "Notes", false, "Project Notes", "Notes"),
        ),
    )

    fun normalise(header: String) = header.lowercase().filter(Char::isLetterOrDigit)

    /** The column for each field: exact synonyms first, then the first column that contains one. */
    fun propose(kind: CsvKind, columns: List<String>): Map<String, String?> {
        val byNormal = columns.associateBy { normalise(it) }
        val taken = mutableSetOf<String>()
        return fields.getValue(kind).associate { field ->
            val column = field.synonyms.firstNotNullOfOrNull { s -> byNormal[normalise(s)]?.takeIf { it !in taken } }
            column?.let { taken += it }
            field.name to column
        }
    }

    /** The kind whose required fields the headers cover best. */
    fun detect(columns: List<String>): CsvKind {
        fun score(kind: CsvKind): Int {
            val mapping = propose(kind, columns)
            val fs = fields.getValue(kind)
            val required = fs.filter { it.required }.count { mapping[it.name] != null }
            val missing = fs.count { it.required } - required
            return if (missing > 0) -100 * missing + mapping.values.count { it != null } else 100 + mapping.values.count { it != null }
        }
        return CsvKind.entries.maxBy(::score)
    }
}

/** Reading values the way people and spreadsheets write them. */
object CsvValues {
    enum class DateOrder { YMD, DMY, MDY }

    private val iso = DateTimeFormatter.ISO_LOCAL_DATE

    /** The date order the values must be in; null when every value fits both day-first and month-first. */
    fun dateOrder(values: Sequence<String>): DateOrder? {
        var dayFirst = false
        var monthFirst = false
        var iso = false
        for (v in values) {
            val parts = v.trim().split('/', '.', '-').map { it.trim() }
            if (parts.size != 3) continue
            if (parts[0].length == 4) {
                iso = true
                continue
            }
            val a = parts[0].toIntOrNull() ?: continue
            val b = parts[1].toIntOrNull() ?: continue
            if (a > 12) dayFirst = true
            if (b > 12) monthFirst = true
        }
        return when {
            iso && !dayFirst && !monthFirst -> DateOrder.YMD
            dayFirst && !monthFirst -> DateOrder.DMY
            monthFirst && !dayFirst -> DateOrder.MDY
            else -> null
        }
    }

    fun date(value: String, order: DateOrder): LocalDate? {
        val v = value.trim()
        runCatching { return LocalDate.parse(v.take(10), iso) }
        val parts = v.split('/', '.', '-').map { it.trim().toIntOrNull() ?: return null }
        if (parts.size != 3) return null
        val (y, m, d) = when (order) {
            DateOrder.YMD -> Triple(parts[0], parts[1], parts[2])
            DateOrder.DMY -> Triple(parts[2], parts[1], parts[0])
            DateOrder.MDY -> Triple(parts[2], parts[0], parts[1])
        }
        val year = if (y < 100) 2000 + y else y
        return runCatching { LocalDate.of(year, m, d) }.getOrNull()
    }

    /** "1.5", "1,5" or "1:30" hours, as seconds. */
    fun hours(value: String): Long? {
        val v = value.trim()
        if (v.contains(':')) {
            val (h, m) = v.split(':').let { (it.getOrNull(0)?.toLongOrNull() ?: return null) to (it.getOrNull(1)?.toLongOrNull() ?: return null) }
            if (m !in 0..59 || h < 0) return null
            return h * 3600 + m * 60
        }
        val n = number(v) ?: return null
        if (n.signum() < 0) return null
        return n.multiply(BigDecimal(3600)).setScale(0, RoundingMode.HALF_UP).toLong()
    }

    /**
     * A number as a spreadsheet writes it: "1,234.50", "1.234,50", "120,00", "€ 95". With both marks,
     * the last is the decimal one; a lone comma followed by one or two digits is decimal.
     */
    fun number(value: String): BigDecimal? {
        var v = value.trim().filter { it.isDigit() || it == '.' || it == ',' || it == '-' }
        if (v.isEmpty() || v == "-") return null
        val comma = v.lastIndexOf(',')
        val dot = v.lastIndexOf('.')
        v = when {
            comma >= 0 && dot >= 0 -> if (comma > dot) v.replace(".", "").replace(',', '.') else v.replace(",", "")
            comma >= 0 -> if (v.length - comma - 1 in 1..2 && v.count { it == ',' } == 1) v.replace(',', '.') else v.replace(",", "")
            else -> v
        }
        return v.toBigDecimalOrNull()
    }

    /** "EUR", "eur" or "Euro - EUR" (as Harvest writes it): the ISO 4217 code, or null. */
    fun currency(value: String): String? =
        Regex("[A-Za-z]{3}").findAll(value).map { it.value.uppercase() }.toList().reversed()
            .firstOrNull { code -> runCatching { java.util.Currency.getInstance(code) }.isSuccess }

    fun yes(value: String?): Boolean =
        value?.trim()?.lowercase() in setOf("yes", "y", "true", "1", "x", "billable", "approved", "invoiced", "ja", "oui", "sí", "si", "da", "sim")
}
