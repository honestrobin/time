// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.importers.harvest

import com.fasterxml.jackson.databind.ObjectMapper
import com.honestrobin.time.db.Tables.CLIENTS
import com.honestrobin.time.db.Tables.EXTERNAL_LINKS
import com.honestrobin.time.db.Tables.IMPORT_JOBS
import com.honestrobin.time.db.Tables.INVOICES
import com.honestrobin.time.db.Tables.PROJECTS
import com.honestrobin.time.db.Tables.TIME_ENTRIES
import com.honestrobin.time.platform.Money
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.YearMonth
import java.util.UUID

/** One project in one month, as Honest Robin and Harvest each count it. */
data class VerificationRow(
    val client: String,
    val project: String,
    val month: String,
    val currency: String,
    val entries: Int,
    val hours: BigDecimal,
    val harvestHours: BigDecimal?,
    val billableAmount: Long,
    val harvestBillableAmount: Long?,
    /** match, rounding (amounts differ only by per-entry rounding) or mismatch */
    val status: String,
)

data class InvoiceCheck(val client: String, val currency: String, val count: Int, val harvestCount: Int, val total: Long, val harvestTotal: Long, val status: String)

data class Verification(
    val entries: Int,
    val invoices: Int,
    val projects: Int,
    val allMatch: Boolean,
    val mismatches: Int,
    val rows: List<VerificationRow>,
    val invoiceChecks: List<InvoiceCheck>,
)

/**
 * The verification screen's data (spec §6.5): for each project and month, the hours and billable
 * amount Honest Robin computes from the imported entries next to what Harvest's time report says,
 * and invoice counts and totals per client. Billable amounts may differ by up to half a minor unit
 * per entry, because Harvest rounds the sum and we round each entry; that is reported as
 * "rounding", not as a mismatch.
 */
@Component
class HarvestVerifier(private val dsl: DSLContext, private val json: ObjectMapper) {

    fun verify(jobId: UUID, accountId: UUID, client: HarvestClient): Verification {
        val imported = DSL.select(EXTERNAL_LINKS.ENTITY_ID).from(EXTERNAL_LINKS)
            .where(EXTERNAL_LINKS.SYSTEM.eq("harvest")).and(EXTERNAL_LINKS.ENTITY_TYPE.eq("time_entry"))
        val month = DSL.field("to_char({0}, 'YYYY-MM')", String::class.java, TIME_ENTRIES.SPENT_DATE)
        val entries = dsl.select(PROJECTS.ID, CLIENTS.NAME, PROJECTS.NAME, CLIENTS.CURRENCY, month, TIME_ENTRIES.DURATION_SECONDS, TIME_ENTRIES.BILLABLE, TIME_ENTRIES.BILLABLE_RATE_SNAPSHOT)
            .from(TIME_ENTRIES).join(PROJECTS).on(PROJECTS.ID.eq(TIME_ENTRIES.PROJECT_ID)).join(CLIENTS).on(CLIENTS.ID.eq(PROJECTS.CLIENT_ID))
            .where(TIME_ENTRIES.ID.`in`(imported))
            .fetch()
        val harvestProjectId = dsl.select(EXTERNAL_LINKS.ENTITY_ID, EXTERNAL_LINKS.EXTERNAL_ID).from(EXTERNAL_LINKS)
            .where(EXTERNAL_LINKS.SYSTEM.eq("harvest")).and(EXTERNAL_LINKS.ENTITY_TYPE.eq("project"))
            .fetch().associate { it.value1() to it.value2() }

        // Ours, per (project, month).
        data class Acc(val client: String, val project: String, val currency: String, var entries: Int = 0, var seconds: Long = 0, var amount: Long = 0)
        val ours = LinkedHashMap<Pair<UUID, String>, Acc>()
        entries.forEach { r ->
            val acc = ours.getOrPut(r.value1() to r.value5()) { Acc(r.value2(), r.value3(), r.value4()) }
            acc.entries++
            acc.seconds += r.value6()
            if (r.value7()) acc.amount += Money.forDuration(r.value6().toLong(), r.value8())
        }

        // Harvest's time report, one request per month that has entries.
        val harvest = HashMap<Pair<String, String>, HTimeReportRow>()
        ours.keys.map { it.second }.distinct().sorted().forEach { m ->
            val ym = YearMonth.parse(m)
            val url = client.url("/reports/time/projects", mapOf("from" to ym.atDay(1), "to" to ym.atEndOfMonth(), "per_page" to 2000))
            client.all(url, "results", HTimeReportRow::class.java).forEach { row -> row.projectId?.let { harvest[it.toString() to m] = row } }
        }

        val rows = ours.map { (key, acc) ->
            val h = harvestProjectId[key.first]?.let { harvest[it to key.second] }
            val hours = BigDecimal(acc.seconds).divide(BigDecimal(3600), 2, RoundingMode.HALF_UP)
            val harvestAmount = h?.let { Money.toMinor(it.billableAmount, acc.currency) }
            val hoursMatch = h != null && hours.compareTo(h.totalHours.setScale(2, RoundingMode.HALF_UP)) == 0
            val amountDiff = harvestAmount?.let { kotlin.math.abs(it - acc.amount) }
            val status = when {
                !hoursMatch || amountDiff == null -> "mismatch"
                amountDiff == 0L -> "match"
                amountDiff <= (acc.entries + 1) / 2 -> "rounding"
                else -> "mismatch"
            }
            VerificationRow(acc.client, acc.project, key.second, acc.currency, acc.entries, hours, h?.totalHours, acc.amount, harvestAmount, status)
        }.sortedWith(compareBy({ it.client }, { it.project }, { it.month }))

        val invoiceChecks = checkInvoices(client)
        val mismatches = rows.count { it.status == "mismatch" } + invoiceChecks.count { it.status == "mismatch" }
        val result = Verification(
            entries = entries.size,
            invoices = invoiceChecks.sumOf { it.count },
            projects = ours.keys.map { it.first }.distinct().size,
            allMatch = mismatches == 0,
            mismatches = mismatches,
            rows = rows,
            invoiceChecks = invoiceChecks,
        )
        dsl.update(IMPORT_JOBS).set(IMPORT_JOBS.VERIFICATION, JSONB.valueOf(json.writeValueAsString(result))).where(IMPORT_JOBS.ID.eq(jobId)).execute()
        return result
    }

    private fun checkInvoices(client: HarvestClient): List<InvoiceCheck> {
        val theirs = client.all(client.url("/invoices", mapOf("per_page" to 2000)), "invoices", HInvoice::class.java)
        val clientIds = dsl.select(EXTERNAL_LINKS.EXTERNAL_ID, EXTERNAL_LINKS.ENTITY_ID).from(EXTERNAL_LINKS)
            .where(EXTERNAL_LINKS.SYSTEM.eq("harvest")).and(EXTERNAL_LINKS.ENTITY_TYPE.eq("client")).fetch().associate { it.value1() to it.value2() }
        val names = dsl.select(CLIENTS.ID, CLIENTS.NAME).from(CLIENTS).fetch().associate { it.value1() to it.value2() }
        val ours = dsl.select(INVOICES.CLIENT_ID, INVOICES.CURRENCY, DSL.count(), DSL.sum(INVOICES.TOTAL_MINOR)).from(INVOICES)
            .where(INVOICES.SOURCE.eq("harvest_import")).groupBy(INVOICES.CLIENT_ID, INVOICES.CURRENCY)
            .fetch().associate { (it.value1() to it.value2()) to (it.value3() to (it.value4()?.toLong() ?: 0L)) }
        return theirs.groupBy { (clientIds[it.client.id.toString()]) to it.currency }.mapNotNull { (key, list) ->
            val clientId = key.first ?: return@mapNotNull null
            val harvestTotal = list.sumOf { Money.toMinor(it.amount, key.second) }
            val (count, total) = ours[clientId to key.second] ?: (0 to 0L)
            InvoiceCheck(names[clientId] ?: "?", key.second, count, list.size, total, harvestTotal, if (count == list.size && total == harvestTotal) "match" else "mismatch")
        }.sortedBy { it.client }
    }
}
