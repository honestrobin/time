// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.accounts

import com.honestrobin.time.db.Tables.MEMBERSHIPS
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Asked before someone is given sign-in access (an invitation). Honest Robin Cloud's billing
 * module answers it from the subscription; everywhere else everyone fits.
 */
fun interface SeatGate {
    fun requireSeat(accountId: UUID)
}

@Component
class OpenSeats : SeatGate {
    override fun requireSeat(accountId: UUID) = Unit
}

/** Seats as billed (spec §14): active people who can sign in. Deactivated people are free. */
@Component
class SeatCounter(private val dsl: DSLContext) {
    fun used(accountId: UUID): Int = dsl.fetchCount(
        MEMBERSHIPS,
        MEMBERSHIPS.ACCOUNT_ID.eq(accountId).and(MEMBERSHIPS.IS_ACTIVE.isTrue).and(MEMBERSHIPS.STATUS.eq("active")),
    )

    /** Seats in use plus invitations that would take one when accepted. */
    fun claimed(accountId: UUID): Int = dsl.fetchCount(
        MEMBERSHIPS,
        MEMBERSHIPS.ACCOUNT_ID.eq(accountId).and(MEMBERSHIPS.IS_ACTIVE.isTrue).and(MEMBERSHIPS.STATUS.`in`("active", "invited")),
    )
}
