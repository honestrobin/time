// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.accounts

import com.honestrobin.time.db.Tables.MEMBERSHIPS
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import java.util.UUID

/** When someone given sign-in access starts to count as a seat: once they accept the invitation, or now. */
enum class SeatStart { WHEN_ACCEPTED, NOW }

/** The price of one seat as an admin saw it and said yes to: per [interval] ("month" or "year"). */
data class SeatPrice(val unitPriceMinor: Long, val currency: String, val interval: String)

/**
 * Asked before someone is given sign-in access: an invitation (also sent again), or bringing
 * someone back. Honest Robin Cloud's billing module answers it from the subscription; everywhere
 * else everyone fits. [confirmed] is the price an admin said yes to, if any: a new paid seat needs
 * it to match the subscription's price now. [counted] says the person already counts towards the
 * plan's limit (an invitation sent again).
 */
fun interface SeatGate {
    fun requireSeat(accountId: UUID, starts: SeatStart, confirmed: SeatPrice?, counted: Boolean)

    /**
     * Asked when someone accepts an invitation, before anything changes. The yes to a paid seat was
     * given when the invitation went out (or at checkout), so this never asks for a price. It only
     * refuses someone the account's plan can't hold any more, so that joining never turns an
     * account read-only. Everywhere but Honest Robin Cloud, everyone fits.
     */
    fun requireSeatToJoin(accountId: UUID) = Unit
}

@Component
class OpenSeats : SeatGate {
    override fun requireSeat(accountId: UUID, starts: SeatStart, confirmed: SeatPrice?, counted: Boolean) = Unit
}

/**
 * Someone in [accountId] can sign in from now on: they accepted an invitation, or came back. The
 * cloud edition's billing listens, so the seat counts from this moment and not before.
 */
data class SeatTaken(val accountId: UUID)

/**
 * Someone in [accountId] can't sign in any more: they were deactivated. The cloud edition's
 * billing listens in the same transaction, so an account that turned read-only when its paid plan
 * ended works again as soon as it's back within the free plan.
 */
data class SeatFreed(val accountId: UUID)

/**
 * Seats as billed (spec §14, decision record 0020): active people who can sign in. Deactivated
 * people are free, and so is an invitation until it's accepted.
 */
@Component
class SeatCounter(private val dsl: DSLContext) {
    fun used(accountId: UUID): Int = dsl.fetchCount(
        MEMBERSHIPS,
        MEMBERSHIPS.ACCOUNT_ID.eq(accountId).and(MEMBERSHIPS.IS_ACTIVE.isTrue).and(MEMBERSHIPS.STATUS.eq("active")),
    )

    /** Invitations not yet accepted: never billed. */
    fun invited(accountId: UUID): Int = dsl.fetchCount(
        MEMBERSHIPS,
        MEMBERSHIPS.ACCOUNT_ID.eq(accountId).and(MEMBERSHIPS.IS_ACTIVE.isTrue).and(MEMBERSHIPS.STATUS.eq("invited")),
    )

    /** People who can sign in plus open invitations: what the free plan's limit counts, never what is billed. */
    fun claimed(accountId: UUID): Int = dsl.fetchCount(
        MEMBERSHIPS,
        MEMBERSHIPS.ACCOUNT_ID.eq(accountId).and(MEMBERSHIPS.IS_ACTIVE.isTrue).and(MEMBERSHIPS.STATUS.`in`("active", "invited")),
    )
}
