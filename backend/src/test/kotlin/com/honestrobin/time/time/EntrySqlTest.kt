// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.time

import com.honestrobin.time.db.Tables.TIME_ENTRIES
import com.honestrobin.time.platform.Money
import com.honestrobin.time.platform.db.DbContext
import com.honestrobin.time.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** The SQL used by reports and budgets rounds and prices every entry exactly like [Money]. */
class EntrySqlTest : IntegrationTest() {

    @Test
    fun `SQL rounding and amounts agree with Money for random entries`() {
        val admin = signup()
        val me = membershipId(admin)
        val task = createTask(admin)
        val project = createProject(admin, taskIds = listOf(task)).id()
        val random = Random(20261003)
        data class Case(val seconds: Int, val rate: Long, val cost: Long, val billable: Boolean)
        val cases = (1..400).map {
            val seconds = when (it % 5) {
                0 -> 0
                1 -> 86_400
                else -> random.nextInt(0, 86_401)
            }
            Case(seconds, random.nextLong(0, 5_000_000), random.nextLong(0, 200_000), random.nextBoolean())
        }
        DbContext.forAccount(admin.accountId!!) {
            tx.run {
                cases.forEach { c ->
                    dsl.insertInto(TIME_ENTRIES).set(TIME_ENTRIES.ACCOUNT_ID, admin.accountId).set(TIME_ENTRIES.MEMBERSHIP_ID, me)
                        .set(TIME_ENTRIES.PROJECT_ID, project).set(TIME_ENTRIES.TASK_ID, task).set(TIME_ENTRIES.SPENT_DATE, java.time.LocalDate.of(2026, 9, 1))
                        .set(TIME_ENTRIES.DURATION_SECONDS, c.seconds).set(TIME_ENTRIES.BILLABLE, c.billable)
                        .set(TIME_ENTRIES.BILLABLE_RATE_SNAPSHOT, c.rate).set(TIME_ENTRIES.COST_RATE_SNAPSHOT, c.cost).execute()
                }
                for (minutes in listOf(0, 6, 15, 30)) {
                    for (mode in listOf("up", "nearest")) {
                        val rounded = EntrySql.rounded(EntrySql.seconds(), minutes, mode)
                        val rows = dsl.select(TIME_ENTRIES.DURATION_SECONDS, TIME_ENTRIES.BILLABLE, TIME_ENTRIES.BILLABLE_RATE_SNAPSHOT, TIME_ENTRIES.COST_RATE_SNAPSHOT,
                            rounded, EntrySql.billableAmount(rounded), EntrySql.cost(EntrySql.seconds()))
                            .from(TIME_ENTRIES).where(TIME_ENTRIES.PROJECT_ID.eq(project)).fetch()
                        assertThat(rows).hasSize(cases.size)
                        rows.forEach { r ->
                            val expected = Money.roundSeconds(r.value1().toLong(), minutes, mode)
                            assertThat(r.value5().toLong()).withFailMessage { "rounding ${r.value1()} s to $minutes min $mode" }.isEqualTo(expected)
                            assertThat(r.value6()).isEqualTo(if (r.value2()) Money.forDuration(expected, r.value3()) else 0L)
                            assertThat(r.value7()).isEqualTo(Money.forDuration(r.value1().toLong(), r.value4()))
                        }
                    }
                }
            }
        }
    }
}
