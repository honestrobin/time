// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.catalog

import com.honestrobin.time.db.Tables.MEMBERSHIPS
import com.honestrobin.time.db.Tables.PROJECTS
import com.honestrobin.time.db.Tables.PROJECT_MEMBERS
import com.honestrobin.time.db.Tables.PROJECT_TASKS
import com.honestrobin.time.db.Tables.TASKS
import com.honestrobin.time.db.Tables.TIME_ENTRIES
import com.honestrobin.time.platform.security.Current
import com.honestrobin.time.platform.security.Member
import com.honestrobin.time.platform.web.ForbiddenException
import io.swagger.v3.oas.annotations.tags.Tag
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.jooq.impl.DSL.coalesce
import org.jooq.impl.DSL.inline
import org.jooq.impl.DSL.`when`
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Billable rate resolution (spec §5.1), matching Harvest's precedence:
 * - bill_by project → project hourly rate
 * - bill_by tasks   → the project's task-assignment rate, else the task's default rate
 * - bill_by people  → the project user-assignment rate unless it uses default rates, else the person's default rate
 * - bill_by none, non-billable project or non-billable entry → 0
 * The cost rate is the person's cost rate. Both are snapshotted onto each entry.
 *
 * This expression is the single source of truth: entry saves and "update rates" both use it.
 */
object RateSql {
    fun billableRate(entryBillable: Field<Boolean>): Field<Long> =
        `when`(entryBillable.isFalse.or(PROJECTS.IS_BILLABLE.isFalse).or(PROJECTS.BILL_BY.eq("none")), inline(0L))
            .`when`(PROJECTS.BILL_BY.eq("project"), coalesce(PROJECTS.HOURLY_RATE, inline(0L)))
            .`when`(PROJECTS.BILL_BY.eq("tasks"), coalesce(PROJECT_TASKS.HOURLY_RATE, TASKS.DEFAULT_RATE, inline(0L)))
            .`when`(
                PROJECTS.BILL_BY.eq("people"),
                `when`(
                    PROJECT_MEMBERS.USE_DEFAULT_RATES.isFalse.and(PROJECT_MEMBERS.HOURLY_RATE.isNotNull),
                    PROJECT_MEMBERS.HOURLY_RATE,
                ).otherwise(coalesce(MEMBERSHIPS.DEFAULT_BILLABLE_RATE, inline(0L))),
            )
            .otherwise(inline(0L))

    val costRate: Field<Long> = coalesce(MEMBERSHIPS.COST_RATE, inline(0L))

    /** Joins everything [billableRate] needs, starting from projects, for one project/task/person. */
    fun resolve(dsl: DSLContext, projectId: UUID, taskId: UUID, membershipId: UUID, billable: Boolean): Pair<Long, Long> {
        val billableField = inline(billable)
        return dsl.select(billableRate(billableField), costRate)
            .from(PROJECTS)
            .crossJoin(TASKS)
            .crossJoin(MEMBERSHIPS)
            .leftJoin(PROJECT_TASKS).on(PROJECT_TASKS.PROJECT_ID.eq(PROJECTS.ID).and(PROJECT_TASKS.TASK_ID.eq(TASKS.ID)))
            .leftJoin(PROJECT_MEMBERS).on(PROJECT_MEMBERS.PROJECT_ID.eq(PROJECTS.ID).and(PROJECT_MEMBERS.MEMBERSHIP_ID.eq(MEMBERSHIPS.ID)))
            .where(PROJECTS.ID.eq(projectId)).and(TASKS.ID.eq(taskId)).and(MEMBERSHIPS.ID.eq(membershipId))
            .fetchOne { it.value1() to it.value2() } ?: (0L to 0L)
    }
}

data class StaleRates(val entries: Int)

/**
 * Rates are snapshotted on entries. When rates change, uninvoiced and unlocked entries
 * are only re-rated after the user confirms ("Update rates on N uninvoiced entries?").
 */
@Service
class RateService(private val dsl: DSLContext, private val access: com.honestrobin.time.accounts.Access) {

    private fun staleCondition(projectId: UUID?, membershipId: UUID?): Condition {
        val resolved = RateSql.billableRate(TIME_ENTRIES.BILLABLE)
        return TIME_ENTRIES.INVOICE_ID.isNull
            .and(TIME_ENTRIES.IS_LOCKED.isFalse)
            .and(if (projectId != null) TIME_ENTRIES.PROJECT_ID.eq(projectId) else DSL.noCondition())
            .and(if (membershipId != null) TIME_ENTRIES.MEMBERSHIP_ID.eq(membershipId) else DSL.noCondition())
            .and(TIME_ENTRIES.BILLABLE_RATE_SNAPSHOT.ne(resolved).or(TIME_ENTRIES.COST_RATE_SNAPSHOT.ne(RateSql.costRate)))
    }

    private fun joined() = dsl.selectCount()
        .from(TIME_ENTRIES)
        .join(PROJECTS).on(PROJECTS.ID.eq(TIME_ENTRIES.PROJECT_ID))
        .join(TASKS).on(TASKS.ID.eq(TIME_ENTRIES.TASK_ID))
        .join(MEMBERSHIPS).on(MEMBERSHIPS.ID.eq(TIME_ENTRIES.MEMBERSHIP_ID))
        .leftJoin(PROJECT_TASKS).on(PROJECT_TASKS.PROJECT_ID.eq(TIME_ENTRIES.PROJECT_ID).and(PROJECT_TASKS.TASK_ID.eq(TIME_ENTRIES.TASK_ID)))
        .leftJoin(PROJECT_MEMBERS).on(PROJECT_MEMBERS.PROJECT_ID.eq(TIME_ENTRIES.PROJECT_ID).and(PROJECT_MEMBERS.MEMBERSHIP_ID.eq(TIME_ENTRIES.MEMBERSHIP_ID)))

    @Transactional(readOnly = true)
    fun stale(m: Member, projectId: UUID?, membershipId: UUID?): StaleRates {
        requireRateManager(m, projectId)
        return StaleRates(joined().where(staleCondition(projectId, membershipId)).fetchOne(0, Int::class.java)!!)
    }

    @Transactional
    fun apply(m: Member, projectId: UUID?, membershipId: UUID?): StaleRates {
        requireRateManager(m, projectId)
        m.requireWritable()
        val te = TIME_ENTRIES.`as`("target")
        // UPDATE … FROM with the same joins as the stale check.
        val updated = dsl.update(te)
            .set(te.BILLABLE_RATE_SNAPSHOT, RateSql.billableRate(TIME_ENTRIES.BILLABLE))
            .set(te.COST_RATE_SNAPSHOT, RateSql.costRate)
            .from(
                TIME_ENTRIES
                    .join(PROJECTS).on(PROJECTS.ID.eq(TIME_ENTRIES.PROJECT_ID))
                    .join(TASKS).on(TASKS.ID.eq(TIME_ENTRIES.TASK_ID))
                    .join(MEMBERSHIPS).on(MEMBERSHIPS.ID.eq(TIME_ENTRIES.MEMBERSHIP_ID))
                    .leftJoin(PROJECT_TASKS).on(PROJECT_TASKS.PROJECT_ID.eq(TIME_ENTRIES.PROJECT_ID).and(PROJECT_TASKS.TASK_ID.eq(TIME_ENTRIES.TASK_ID)))
                    .leftJoin(PROJECT_MEMBERS).on(PROJECT_MEMBERS.PROJECT_ID.eq(TIME_ENTRIES.PROJECT_ID).and(PROJECT_MEMBERS.MEMBERSHIP_ID.eq(TIME_ENTRIES.MEMBERSHIP_ID))),
            )
            .where(te.ID.eq(TIME_ENTRIES.ID))
            .and(staleCondition(projectId, membershipId))
            .execute()
        return StaleRates(updated)
    }

    private fun requireRateManager(m: Member, projectId: UUID?) {
        if (!m.canSeeRates || !m.isManagerOrAdmin) throw ForbiddenException("You don't have permission to manage rates")
        if (!m.isAdmin && (projectId == null || !access.manages(m, projectId))) throw ForbiddenException("You don't manage this project")
    }
}

data class ApplyRatesRequest(val projectId: UUID? = null, val membershipId: UUID? = null)

@RestController
@RequestMapping("/api/v1/rates")
@Tag(name = "rates", description = "Re-rating uninvoiced entries after rates change")
class RateController(private val rates: RateService) {
    /** How many unlocked, uninvoiced entries carry a rate that differs from the current configuration. */
    @GetMapping("/stale")
    fun stale(
        @RequestParam(name = "project_id", required = false) projectId: UUID?,
        @RequestParam(name = "membership_id", required = false) membershipId: UUID?,
    ) = rates.stale(Current.member(), projectId, membershipId)

    /** Applies current rates to those entries. */
    @PostMapping("/apply")
    fun apply(@RequestBody body: ApplyRatesRequest) = rates.apply(Current.member(), body.projectId, body.membershipId)
}
