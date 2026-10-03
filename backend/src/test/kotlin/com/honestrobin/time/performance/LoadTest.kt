// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.performance

import com.honestrobin.time.platform.db.DbContext
import com.honestrobin.time.platform.db.TenantAwareTransactionManager
import com.honestrobin.time.support.IntegrationTest
import com.honestrobin.time.support.TestClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.UUID

/**
 * AT-6.4: one account with 1M time entries; reports answer within 1 s and the week timesheet
 * within 300 ms at the 95th percentile, measured through the API (JSON included).
 * Takes a few minutes, so it runs only with PERF=1 (`PERF=1 ./gradlew :backend:test --tests '*LoadTest'`).
 */
@EnabledIfEnvironmentVariable(named = "PERF", matches = "1")
class LoadTest : IntegrationTest() {

    private val entries = (System.getenv("PERF_ENTRIES") ?: "1000000").toInt()

    @Test
    fun `reports and timesheets stay fast with a million entries (AT-6_4)`() {
        val admin = signup(accountName = "Big Agency")
        val account = admin.accountId!!
        val me = membershipId(admin)
        val started = System.currentTimeMillis()
        seed(account, me)
        println("Seeded $entries entries in ${(System.currentTimeMillis() - started) / 1000} s")

        val year = mapOf("from" to "2026-01-01", "to" to "2026-12-31")
        val results = linkedMapOf<String, Long>()
        fun measure(name: String, runs: Int = 20, call: (TestClient) -> Unit) {
            repeat(3) { call(admin) } // warm up
            val times = (1..runs).map {
                val t = System.nanoTime()
                call(admin)
                (System.nanoTime() - t) / 1_000_000
            }.sorted()
            results[name] = times[(runs * 95 / 100).coerceAtMost(runs - 1)]
            println("$name: p50 ${times[runs / 2]} ms, p95 ${results[name]} ms")
        }
        for (g in listOf("client", "project", "task", "person")) {
            measure("time report by $g, one year") { it.get("/api/v1/reports/time", year + ("group_by" to g)).expect(200) }
        }
        measure("time report, all time") { it.get("/api/v1/reports/time", mapOf("group_by" to "project")).expect(200) }
        measure("detailed report, first page") { it.get("/api/v1/reports/detailed", year + ("limit" to 200)).expect(200) }
        measure("uninvoiced report, all time") { it.get("/api/v1/reports/uninvoiced").expect(200) }
        measure("budget report") { it.get("/api/v1/reports/budget").expect(200) }
        measure("week timesheet") { it.get("/api/v1/timesheets/week", mapOf("start" to "2026-09-14")).expect(200) }

        results.filterKeys { it != "week timesheet" }.forEach { (name, p95) -> assertThat(p95).withFailMessage { "$name: p95 $p95 ms" }.isLessThan(1000) }
        assertThat(results["week timesheet"]!!).withFailMessage { "week timesheet: p95 ${results["week timesheet"]} ms" }.isLessThan(300)
    }

    /**
     * 40 people, 12 clients, 60 projects with budgets, 8 tasks, and [entries] entries over three
     * years, written in SQL so seeding takes seconds rather than hours.
     */
    private fun seed(account: UUID, me: UUID) {
        DbContext.forAccount(account) {
            tx.run {
                TenantAwareTransactionManager.setLocal("honestrobin.audit_disabled", "on")
                dsl.execute(
                    """
                    insert into memberships (account_id, name, email, role, status, default_billable_rate, cost_rate)
                    select '$account', 'Person ' || g, 'person' || g || '@load.test', 'member', 'active', 9000 + g * 100, 4000
                    from generate_series(1, 39) g;
                    insert into clients (account_id, name, currency)
                    select '$account', 'Client ' || g, (array['EUR','USD','GBP','JPY'])[1 + g % 4] from generate_series(1, 12) g;
                    insert into tasks (account_id, name) select '$account', 'Task ' || g from generate_series(1, 8) g;
                    insert into projects (account_id, client_id, name, bill_by, hourly_rate, budget_by, budget_seconds)
                    select '$account', c.id, c.name || ' project ' || p, 'project', 12000, 'project', 2000 * 3600
                    from clients c, generate_series(1, 5) p where c.account_id = '$account';
                    insert into project_tasks (account_id, project_id, task_id)
                    select '$account', p.id, t.id from projects p, tasks t where p.account_id = '$account' and t.account_id = '$account';
                    insert into project_members (account_id, project_id, membership_id)
                    select '$account', p.id, m.id from projects p, memberships m where p.account_id = '$account' and m.account_id = '$account';
                    """.trimIndent(),
                )
                dsl.execute(
                    """
                    with p as (select array_agg(id order by id) ids from projects where account_id = '$account'),
                         t as (select array_agg(id order by id) ids from tasks where account_id = '$account'),
                         m as (select array_agg(id order by id) ids from memberships where account_id = '$account')
                    insert into time_entries (account_id, membership_id, project_id, task_id, spent_date, duration_seconds, notes, billable,
                                              billable_rate_snapshot, cost_rate_snapshot, approval_state)
                    select '$account',
                           m.ids[1 + (g * 7) % array_length(m.ids, 1)],
                           p.ids[1 + (g * 13) % array_length(p.ids, 1)],
                           t.ids[1 + (g * 3) % array_length(t.ids, 1)],
                           date '2024-01-01' + (g % 1096),
                           900 + (g * 37) % 27000,
                           'Work item ' || g,
                           g % 5 <> 0,
                           12000, 4000,
                           case when g % 1096 < 1000 then 'approved' else 'draft' end
                    from generate_series(1, $entries) g, p, t, m
                    """.trimIndent(),
                )
                // Give the timesheet's own week some entries of the signed-in admin.
                dsl.execute(
                    """
                    insert into time_entries (account_id, membership_id, project_id, task_id, spent_date, duration_seconds, billable, billable_rate_snapshot)
                    select '$account', '$me', pt.project_id, pt.task_id, date '2026-09-14' + (g % 5), 3600, true, 12000
                    from (select project_id, task_id from project_tasks where account_id = '$account' limit 8) pt, generate_series(1, 5) g
                    """.trimIndent(),
                )
                dsl.execute("insert into project_members (account_id, project_id, membership_id) select '$account', id, '$me' from projects where account_id = '$account' on conflict do nothing")
            }
        }
        tx.system { dsl.execute("analyze") }
    }
}
