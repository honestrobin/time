// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.accounts

import com.honestrobin.time.db.Tables.ACCOUNTS
import com.honestrobin.time.db.Tables.MEMBERSHIPS
import com.honestrobin.time.db.Tables.USERS
import com.honestrobin.time.platform.db.Tx
import com.honestrobin.time.platform.security.Member
import com.honestrobin.time.platform.security.Role
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.Record
import org.springframework.stereotype.Component
import java.util.UUID

/** Resolves the caller's membership for a request. Runs cross-tenant because it decides the tenant. */
@Component
class MembershipResolver(private val dsl: DSLContext, private val tx: Tx) {

    /** The membership of [userId] in [accountId], or their only account when [accountId] is null. */
    fun resolve(userId: UUID, accountId: UUID?): Member? = tx.system {
        val rows = query(MEMBERSHIPS.USER_ID.eq(userId).let { if (accountId != null) it.and(MEMBERSHIPS.ACCOUNT_ID.eq(accountId)) else it })
        if (accountId == null && rows.size != 1) null else rows.firstOrNull()
    }

    fun byMembershipId(membershipId: UUID): Member? = tx.system { query(MEMBERSHIPS.ID.eq(membershipId)).firstOrNull() }

    private fun query(condition: Condition): List<Member> =
        dsl.select(MEMBERSHIPS.asterisk(), ACCOUNTS.STATUS, TWO_FACTOR_MISSING)
            .from(MEMBERSHIPS).join(ACCOUNTS).on(ACCOUNTS.ID.eq(MEMBERSHIPS.ACCOUNT_ID))
            .join(USERS).on(USERS.ID.eq(MEMBERSHIPS.USER_ID))
            .where(condition)
            .and(MEMBERSHIPS.IS_ACTIVE.isTrue)
            .and(MEMBERSHIPS.STATUS.eq("active"))
            .and(MEMBERSHIPS.USER_ID.isNotNull)
            .fetch { toMember(it) }

    companion object {
        private val TWO_FACTOR_MISSING = DSL.field(ACCOUNTS.REQUIRE_TWO_FACTOR.isTrue.and(USERS.TOTP_ENABLED_AT.isNull)).`as`("two_factor_missing")

        fun toMember(r: Record): Member {
            val role = Role.of(r[MEMBERSHIPS.ROLE])
            val admin = role == Role.ADMIN
            return Member(
                membershipId = r[MEMBERSHIPS.ID],
                accountId = r[MEMBERSHIPS.ACCOUNT_ID],
                userId = r[MEMBERSHIPS.USER_ID],
                role = role,
                name = r[MEMBERSHIPS.NAME],
                canSeeRates = admin || r[MEMBERSHIPS.CAN_SEE_RATES],
                canManageProjects = admin || (role == Role.MANAGER && r[MEMBERSHIPS.CAN_MANAGE_PROJECTS]),
                canManageInvoices = admin || (role == Role.MANAGER && r[MEMBERSHIPS.CAN_MANAGE_INVOICES]),
                accountStatus = r[ACCOUNTS.STATUS],
                twoFactorMissing = r[TWO_FACTOR_MISSING],
            )
        }
    }
}
