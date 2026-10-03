// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.importers

import com.honestrobin.time.importers.csv.CsvFields
import com.honestrobin.time.importers.csv.CsvKind
import com.honestrobin.time.importers.csv.CsvTable
import com.honestrobin.time.importers.csv.CsvValues
import com.honestrobin.time.importers.csv.CsvValues.DateOrder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** Reading CSV files the way Harvest and spreadsheet apps write them (spec §6.6). */
class CsvValuesTest {

    @Test
    fun `hours as decimals with either mark, or hours and minutes`() {
        assertThat(CsvValues.hours("1.5")).isEqualTo(5400)
        assertThat(CsvValues.hours("1,5")).isEqualTo(5400)
        assertThat(CsvValues.hours("0.25")).isEqualTo(900)
        assertThat(CsvValues.hours("2:45")).isEqualTo(9900)
        assertThat(CsvValues.hours("2:75")).isNull()
        assertThat(CsvValues.hours("-1")).isNull()
        assertThat(CsvValues.hours("soon")).isNull()
    }

    @Test
    fun `amounts with thousands separators and currency signs`() {
        assertThat(CsvValues.number("1,234.50")).isEqualByComparingTo("1234.50")
        assertThat(CsvValues.number("1.234,50")).isEqualByComparingTo("1234.50")
        assertThat(CsvValues.number("120,00")).isEqualByComparingTo("120.00")
        assertThat(CsvValues.number("1,200")).isEqualByComparingTo("1200")
        assertThat(CsvValues.number("€ 95")).isEqualByComparingTo(BigDecimal(95))
        assertThat(CsvValues.number("")).isNull()
    }

    @Test
    fun `currencies as codes or as Harvest writes them`() {
        assertThat(CsvValues.currency("EUR")).isEqualTo("EUR")
        assertThat(CsvValues.currency("nzd")).isEqualTo("NZD")
        assertThat(CsvValues.currency("New Zealand Dollar - NZD")).isEqualTo("NZD")
        assertThat(CsvValues.currency("Euro - EUR")).isEqualTo("EUR")
        assertThat(CsvValues.currency("money")).isNull()
    }

    @Test
    fun `dates in ISO, day-first or month-first, and when it can't be told`() {
        assertThat(CsvValues.dateOrder(sequenceOf("2026-01-31", "2026-02-01"))).isEqualTo(DateOrder.YMD)
        assertThat(CsvValues.dateOrder(sequenceOf("01.02.2026", "31.01.2026"))).isEqualTo(DateOrder.DMY)
        assertThat(CsvValues.dateOrder(sequenceOf("01/02/2026", "01/31/2026"))).isEqualTo(DateOrder.MDY)
        assertThat(CsvValues.dateOrder(sequenceOf("01/02/2026", "03/04/2026"))).isNull()
        assertThat(CsvValues.date("31.01.2026", DateOrder.DMY)).isEqualTo(LocalDate.of(2026, 1, 31))
        assertThat(CsvValues.date("1/31/26", DateOrder.MDY)).isEqualTo(LocalDate.of(2026, 1, 31))
        assertThat(CsvValues.date("2026-01-31", DateOrder.DMY)).isEqualTo(LocalDate.of(2026, 1, 31))
        assertThat(CsvValues.date("31/02/2026", DateOrder.DMY)).isNull()
    }

    @Test
    fun `files in UTF-8 with a mark, UTF-16 and Windows-1252, with commas, semicolons or tabs`() {
        val utf8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Date,Client,Notes\n2026-01-02,Zoë Café,\"Said \"\"hi\"\", then left\"\n".toByteArray()
        val t = CsvTable.read(utf8)
        assertThat(t.columns).containsExactly("Date", "Client", "Notes")
        assertThat(t.rows.single()).containsExactly("2026-01-02", "Zoë Café", "Said \"hi\", then left")

        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Datum\tKunde\n02.01.2026\tMüller\n".toByteArray(Charsets.UTF_16LE)
        assertThat(CsvTable.read(utf16).rows.single()).containsExactly("02.01.2026", "Müller")

        val cp1252 = "Datum;Kunde\n02.01.2026;Müller\n".toByteArray(charset("windows-1252"))
        assertThat(CsvTable.read(cp1252).rows.single()).containsExactly("02.01.2026", "Müller")
    }

    @Test
    fun `Harvest's exports are recognised by their headers`() {
        val time = listOf("Date", "Client", "Project", "Project Code", "Task", "Notes", "Hours", "Hours Rounded", "Billable?", "Invoiced?", "Approved?", "First Name", "Last Name", "Billable Rate", "Currency")
        assertThat(CsvFields.detect(time)).isEqualTo(CsvKind.TIME)
        assertThat(CsvFields.propose(CsvKind.TIME, time)).containsEntry("billable", "Billable?").containsEntry("first_name", "First Name").containsEntry("project_code", "Project Code")
        assertThat(CsvFields.detect(listOf("Client Name", "Client Address", "Client Currency"))).isEqualTo(CsvKind.CLIENTS)
        assertThat(CsvFields.detect(listOf("Client", "Title", "First Name", "Last Name", "Email", "Office Phone"))).isEqualTo(CsvKind.CONTACTS)
        assertThat(CsvFields.detect(listOf("Client", "Project", "Project Code", "Billable?", "Project Notes"))).isEqualTo(CsvKind.PROJECTS)
    }
}
