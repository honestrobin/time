// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.billing

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay
import com.honestrobin.time.accounts.SeatCounter
import com.honestrobin.time.accounts.SeatFreed
import com.honestrobin.time.accounts.SeatGate
import com.honestrobin.time.accounts.SeatPrice
import com.honestrobin.time.accounts.SeatStart
import com.honestrobin.time.accounts.SeatTaken
import com.honestrobin.time.analytics.Funnel
import com.honestrobin.time.db.Tables.ACCOUNTS
import com.honestrobin.time.db.Tables.BILLING_EVENTS
import com.honestrobin.time.db.Tables.MEMBERSHIPS
import com.honestrobin.time.db.Tables.SUBSCRIPTIONS
import com.honestrobin.time.db.Tables.USERS
import com.honestrobin.time.db.tables.records.SubscriptionsRecord
import com.honestrobin.time.export.AccountPurging
import com.honestrobin.time.platform.HonestRobinProperties
import com.honestrobin.time.platform.Money
import com.honestrobin.time.platform.db.DbContext
import com.honestrobin.time.platform.db.Tx
import com.honestrobin.time.platform.edition.CloudEditionOnly
import com.honestrobin.time.platform.mail.Mailer
import com.honestrobin.time.platform.security.Current
import com.honestrobin.time.platform.security.Member
import com.honestrobin.time.platform.web.ApiException
import com.honestrobin.time.platform.web.ConflictException
import com.honestrobin.time.platform.web.ValidationException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class PlanPrice(val interval: String, val currency: String, val perSeatPerMonthMinor: Long)

/**
 * Paddle reported a price per seat above the subscription's locked one (decision record 0023). The
 * lock stays, and if Paddle charges more, the difference is refunded by hand.
 */
data class PriceReport(
    val reportedUnitPriceMinor: Long,
    /** The locked price when Paddle reported it. */
    val lockedUnitPriceMinor: Long,
    val currency: String,
    /** The billing period both prices are for: month or year. */
    val interval: String,
    /** The end of the billing period it was reported for, if Paddle said. */
    val periodEnd: Instant?,
    val reportedAt: Instant,
    /** A person has refunded the difference and marked it so. */
    val refunded: Boolean,
)

data class SubscriptionView(
    /** free (one person) or team. */
    val plan: String,
    val status: String,
    /** People who can sign in now: what checkout and Paddle count, and so what a Team subscription is billed for. */
    val seatsUsed: Int,
    /** Invitations not yet accepted. They're not billed; each counts from the moment it's accepted. */
    val invitationsPending: Int,
    val seatsBilled: Int?,
    val interval: String?,
    val currency: String?,
    /** The price per seat this subscription keeps for as long as it runs (spec §14). */
    val lockedUnitPriceMinor: Long?,
    val currentPeriodEnd: Instant?,
    val cancelAt: Instant?,
    /** Whether Paddle is set up on this instance; without it there is nothing to buy. */
    val billingAvailable: Boolean,
    val prices: List<PlanPrice>,
    /** How many people the free plan has room for: a lapsed account works again once no more than this can sign in. */
    val freePlanSeats: Int,
    /** Every price Paddle reported above the lock, refunded or not (`refunded` says which); usually none. */
    val priceReports: List<PriceReport>,
)

data class CheckoutRequest(val interval: String = "year")

/**
 * When the cancellation the admin saw takes effect: `period_end` (the usual case), or `now`, which
 * the app offers only while a payment is past due. A request that doesn't match what's true by
 * the time it arrives is refused, so the plan never ends at once without a yes to that.
 */
data class CancelRequest(val ends: String = "period_end")

/** What Paddle.js needs to open the checkout in the browser. */
data class CheckoutView(
    val environment: String,
    val clientToken: String,
    val priceId: String,
    val quantity: Int,
    val email: String,
    val customData: Map<String, String>,
)

data class PortalView(val url: String)

/**
 * Honest Robin Cloud subscriptions (spec §14): Free for one person, Team per seat. Paddle is the
 * merchant of record; this keeps the state Paddle reports, keeps the seat count in step, and
 * never lets a subscription's price per seat go up (the trigger `subscriptions_price_lock` backs
 * this in the database).
 */
@Service
@CloudEditionOnly
class BillingService(
    private val dsl: DSLContext,
    private val tx: Tx,
    private val settings: PaddleSettings,
    private val prices: BillingPrices,
    private val seats: SeatCounter,
    private val freePlan: FreePlan,
    private val funnel: Funnel,
    private val json: ObjectMapper,
    private val clock: Clock,
    private val mailer: Mailer,
    private val props: HonestRobinProperties,
    transactions: PlatformTransactionManager,
) {
    companion object {
        /**
         * Our own billing event: Paddle reported a price per seat above the lock. Its id carries
         * what the billing page shows, without a column of its own:
         * `price_above_lock:<subscription>:<end of the billing period, epoch seconds, or 0>:<currency>:<month or year>:<locked price>:<reported price>`
         * (prices in minor units). So each price is recorded once per billing period, and stays
         * readable after the subscription is replaced or the refund is marked.
         */
        const val PRICE_ABOVE_LOCK = "price_above_lock"

        /** The same event once the difference has been refunded: a person sets it, by hand, for now. */
        const val PRICE_ABOVE_LOCK_REFUNDED = "price_above_lock_refunded"
    }

    private val log = LoggerFactory.getLogger(javaClass)
    private val paddle = PaddleClient(settings, json)

    /**
     * A short transaction of its own, never joined to the caller's: seat changes run after the
     * change that caused them committed, where joining would write into a finished transaction.
     */
    private val alone = TransactionTemplate(transactions).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    @Suppress("UNCHECKED_CAST")
    private fun <T> system(block: () -> T): T = alone.execute { DbContext.system(block) } as T

    private fun current(): SubscriptionsRecord? = dsl.selectFrom(SUBSCRIPTIONS).fetchOne()

    /** A subscription that pays for seats now. */
    fun isPaying(s: SubscriptionsRecord?) = s != null && s.status in setOf("trialing", "active", "past_due")

    @Transactional(readOnly = true)
    fun view(m: Member): SubscriptionView {
        m.requireAdmin()
        val s = current()
        val paying = isPaying(s)
        return SubscriptionView(
            plan = if (paying) "team" else "free", status = s?.status ?: "active", seatsUsed = seats.used(m.accountId),
            invitationsPending = seats.invited(m.accountId), seatsBilled = s?.seats, interval = s?.billingInterval, currency = s?.currency, lockedUnitPriceMinor = s?.lockedUnitPriceMinor,
            currentPeriodEnd = s?.currentPeriodEnd, cancelAt = s?.cancelAt, billingAvailable = settings.configured,
            prices = listOf(PlanPrice("month", prices.currency, prices.teamMonthlyMinor), PlanPrice("year", prices.currency, prices.teamAnnualMonthlyMinor)),
            freePlanSeats = freePlan.seats,
            priceReports = priceReports(m.accountId),
        )
    }

    /**
     * Every price Paddle reported above the account's lock, oldest first, refunded or not: the
     * billing page shows the ones still open, and the API keeps all of them in reach.
     */
    private fun priceReports(accountId: UUID): List<PriceReport> =
        dsl.select(BILLING_EVENTS.EVENT_ID, BILLING_EVENTS.EVENT_TYPE, BILLING_EVENTS.RECEIVED_AT).from(BILLING_EVENTS)
            .where(BILLING_EVENTS.ACCOUNT_ID.eq(accountId)).and(BILLING_EVENTS.EVENT_TYPE.`in`(PRICE_ABOVE_LOCK, PRICE_ABOVE_LOCK_REFUNDED))
            .orderBy(BILLING_EVENTS.RECEIVED_AT).fetch()
            .mapNotNull { row ->
                val parts = row.value1().split(':')
                if (parts.size != 7) return@mapNotNull null
                val periodEnd = parts[2].toLongOrNull()?.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it) }
                val locked = parts[5].toLongOrNull() ?: return@mapNotNull null
                val reported = parts[6].toLongOrNull() ?: return@mapNotNull null
                PriceReport(reported, locked, parts[3], parts[4], periodEnd, row.value3(), refunded = row.value2() == PRICE_ABOVE_LOCK_REFUNDED)
            }

    @Transactional(readOnly = true)
    fun checkout(m: Member, input: CheckoutRequest): CheckoutView {
        m.requireAdmin()
        if (!settings.configured) throw ConflictException("billing_unavailable", "Billing isn't set up on this instance")
        if (isPaying(current())) throw ConflictException("already_subscribed", "This account already has a subscription; change it in the billing portal")
        val priceId = when (input.interval) {
            "month" -> settings.teamMonthlyPriceId
            "year" -> settings.teamAnnualPriceId.ifBlank { throw ValidationException("interval", "Yearly billing isn't offered yet") }
            else -> throw ValidationException("interval", "Choose month or year")
        }
        val email = dsl.select(USERS.EMAIL).from(USERS).where(USERS.ID.eq(m.userId)).fetchOne()!!.value1()
        return CheckoutView(
            environment = settings.environment, clientToken = settings.clientToken, priceId = priceId,
            // People who can sign in. An invitation isn't billed: it counts from the moment it's accepted.
            quantity = maxOf(1, seats.used(m.accountId)), email = email,
            // The browser hands this to Paddle; signed, so nobody can attach a subscription to another account.
            customData = mapOf("account_id" to m.accountId.toString(), "signature" to accountSignature(m.accountId)),
        )
    }

    @Transactional(readOnly = true)
    fun portal(m: Member): PortalView {
        m.requireAdmin()
        val s = current() ?: throw ConflictException("no_subscription", "This account has no subscription yet")
        val customer = s.externalCustomerId ?: throw ConflictException("no_subscription", "This account has no subscription yet")
        return PortalView(paddle.portalUrl(customer, s.externalId))
    }

    /**
     * Cancels the Team plan (the Robin's Code: "Cancel in the app, in two clicks"; decision record
     * 0022). Normally at the end of the period that's paid for: nothing changes until then, Paddle
     * doesn't renew it, and its webhook ends the subscription on that day like any other
     * cancellation. While a payment is past due, that period isn't paid for and Paddle takes no
     * scheduled change, so the plan ends now. Whether a payment is past due is asked of Paddle
     * itself, under the lock, not read from our record, which may not have heard of a payment yet.
     * [ends] is what the admin saw; when it isn't what's true now, nothing changes and the answer
     * says which it is.
     *
     * Our record changes only once Paddle has said yes, in the same transaction, which holds the
     * subscription's lock while Paddle answers: a second click waits and then finds it done, and
     * Paddle's webhook for the change waits too. If we stop between Paddle's yes and our commit,
     * that webhook brings our record in line.
     */
    fun cancel(m: Member, ends: String) {
        m.requireAdmin()
        if (ends !in setOf("period_end", "now")) throw ValidationException("ends", "Choose period_end or now")
        withPaddle(m.accountId) { s ->
            if (s.cancelAt != null) return@withPaddle
            val pastDue = paddle.subscription(s.externalId)["data"]?.get("status")?.asText() == "past_due"
            val now = if (pastDue) "now" else "period_end"
            if (ends != now) {
                throw ConflictException(
                    "cancellation_changed",
                    if (pastDue) {
                        "The last payment didn't go through, so this period isn't paid for, and cancelling ends the Team plan now. Nothing changed yet. To go ahead, send it again with ends=now."
                    } else {
                        "This period is paid for, so cancelling ends the Team plan at the end of it. Nothing changed yet. To go ahead, send it again with ends=period_end."
                    },
                    details = mapOf("ends" to now),
                )
            }
            when {
                pastDue -> {
                    paddle.cancel(s.externalId)
                    s.status = "canceled"
                    s.canceledAt = Instant.now(clock)
                    s.store()
                    lapseIfOverFree(s.accountId)
                    log.info("Account {} cancelled subscription {} while a payment was past due: ended now", m.accountId, s.externalId)
                }
                else -> {
                    val answer = paddle.cancelAtPeriodEnd(s.externalId)
                    // The day Paddle says it takes effect; its webhook says the same.
                    s.cancelAt = scheduledCancel(answer["data"])
                        ?: answer["data"]?.get("current_billing_period")?.get("ends_at")?.asText()?.let(Instant::parse)
                        ?: s.currentPeriodEnd
                    s.store()
                    log.info("Account {} cancelled subscription {} at the end of its period", m.accountId, s.externalId)
                }
            }
        }
    }

    /**
     * Takes back a cancellation before it happens: the Team plan renews as before, on the same
     * day and at the same locked price. Nothing is charged now.
     */
    fun keep(m: Member) {
        m.requireAdmin()
        withPaddle(m.accountId) { s ->
            if (s.cancelAt != null) {
                paddle.removeScheduledChange(s.externalId)
                s.cancelAt = null
                s.store()
                log.info("Account {} kept subscription {}", m.accountId, s.externalId)
            }
        }
    }

    /**
     * Runs [change] on the account's running subscription in a transaction of its own that holds
     * the subscription's lock (the one webhooks take), and keeps it only if Paddle said yes.
     */
    private fun withPaddle(accountId: UUID, change: (SubscriptionsRecord) -> Unit) {
        try {
            system {
                val running = dsl.selectFrom(SUBSCRIPTIONS).where(SUBSCRIPTIONS.ACCOUNT_ID.eq(accountId)).fetchOne()?.takeIf { isPaying(it) }
                    ?: throw ConflictException("no_subscription", "This account has no Team plan running")
                dsl.execute("select pg_advisory_xact_lock(hashtextextended(?, 7234004))", "paddle:${running.externalId}")
                // Read again under the lock: a click or a webhook just before may have changed it.
                val s = dsl.selectFrom(SUBSCRIPTIONS).where(SUBSCRIPTIONS.ID.eq(running.id)).fetchOne()?.takeIf { isPaying(it) }
                    ?: throw ConflictException("no_subscription", "This account has no Team plan running")
                change(s)
            }
        } catch (e: PaddleException) {
            log.warn("Paddle didn't take a change to account {}'s subscription: {} {}", accountId, e.status, e.message)
            throw if (e.status in 400..499) {
                ConflictException(
                    "billing_provider_refused",
                    "Paddle didn't accept the change, so nothing changed. Paddle takes no changes in the 30 minutes before a renewal. " +
                        "If that's not it, write to us and a person will help.",
                )
            } else {
                // Without an answer we can't know whether Paddle did it; its webhook will say.
                ApiException(
                    HttpStatus.BAD_GATEWAY, "billing_provider_unavailable",
                    "Paddle didn't answer, so we don't know yet whether it took the change. If it did, this page shows it within a few minutes; if not, try again.",
                )
            }
        }
    }

    /** When a subscription's scheduled cancellation takes effect, if it has one. */
    private fun scheduledCancel(data: JsonNode?): Instant? =
        data?.get("scheduled_change")?.takeIf { it["action"]?.asText() == "cancel" }?.get("effective_at")?.asText()?.let(Instant::parse)

    /** Handles a Paddle webhook; false when the signature doesn't check out. */
    fun webhook(payload: String, signature: String?): Boolean {
        // Signatures carry wall-clock time, so they are checked against it, not the app clock.
        if (!PaddleClient.verifySignature(payload, signature, settings.webhookSecret, Instant.now())) return false
        val event = json.readTree(payload)
        val type = event["event_type"]?.asText() ?: return true
        if (!type.startsWith("subscription.")) return true
        val data = event["data"]
        val externalId = data["id"].asText()
        val occurred = event["occurred_at"]?.asText()?.let { runCatching { Instant.parse(it) }.getOrNull() }
        var duplicate: String? = null
        tx.system {
            // One event per subscription at a time, so a retry can't race a newer event.
            dsl.execute("select pg_advisory_xact_lock(hashtextextended(?, 7234004))", "paddle:$externalId")
            val existing = dsl.selectFrom(SUBSCRIPTIONS).where(SUBSCRIPTIONS.EXTERNAL_ID.eq(externalId)).fetchOne()
            val accountId = existing?.accountId ?: claimedAccount(data)
            if (accountId == null || !dsl.fetchExists(ACCOUNTS, ACCOUNTS.ID.eq(accountId))) {
                log.warn("Paddle event {} for subscription {} names no known account, or its signature is wrong; ignored", event["event_id"]?.asText(), externalId)
                return@system
            }
            val priceId = data["items"]?.firstOrNull()?.get("price")?.get("id")?.asText()
            if (existing == null && priceId !in setOf(settings.teamMonthlyPriceId, settings.teamAnnualPriceId).filter { it.isNotBlank() }) {
                log.error("Paddle subscription {} is on price {}, which isn't one of ours; ignored", externalId, priceId)
                return@system
            }
            val other = dsl.selectFrom(SUBSCRIPTIONS).where(SUBSCRIPTIONS.ACCOUNT_ID.eq(accountId)).and(SUBSCRIPTIONS.EXTERNAL_ID.ne(externalId)).fetchOne()
            if (other != null && isPaying(other)) {
                // Two checkouts at once (two tabs, say) make a second subscription. It's cancelled so
                // it never renews; its first payment needs a refund by a person.
                if (data["status"]?.asText() in setOf("trialing", "active", "past_due")) duplicate = externalId
                log.error("Paddle subscription {} is for account {}, which already pays through {}; cancelling it, refund its first payment", externalId, accountId, other.externalId)
                return@system
            }
            other?.delete()
            // Paddle retries: each event once.
            val fresh = dsl.insertInto(BILLING_EVENTS).set(BILLING_EVENTS.EVENT_ID, event["event_id"].asText()).set(BILLING_EVENTS.EVENT_TYPE, type)
                .set(BILLING_EVENTS.ACCOUNT_ID, accountId).onConflictDoNothing().execute()
            if (fresh == 0) return@system
            // Paddle doesn't promise order: an event older than the one last applied is only recorded.
            val last = existing?.lastEventAt
            if (last != null && (occurred == null || occurred.isBefore(last))) {
                log.info("Paddle event {} for subscription {} happened before the one already applied; not applied", event["event_id"]?.asText(), externalId)
                return@system
            }
            DbContext.forAccount(accountId) { apply(accountId, existing, data, occurred) }
        }
        duplicate?.let { id ->
            try {
                paddle.cancel(id)
            } catch (e: PaddleException) {
                log.error("Cancelling duplicate Paddle subscription {} failed: {}", id, e.message)
            }
        }
        return true
    }

    /** The account a new subscription is for, if its signature from our checkout checks out. */
    private fun claimedAccount(data: JsonNode): UUID? {
        val custom = data["custom_data"] ?: return null
        val account = custom["account_id"]?.asText()?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
        val signature = custom["signature"]?.asText() ?: return null
        return account.takeIf { java.security.MessageDigest.isEqual(signature.toByteArray(), accountSignature(it).toByteArray()) }
    }

    private fun accountSignature(accountId: UUID): String = PaddleClient.sign(settings.webhookSecret, 0, "account:$accountId")

    private fun apply(accountId: UUID, existing: SubscriptionsRecord?, data: JsonNode, occurred: Instant?) {
        val item = data["items"]?.firstOrNull() ?: return
        val unitPrice = item["price"]["unit_price"]["amount"].asText().toLong()
        val r = existing ?: dsl.newRecord(SUBSCRIPTIONS).apply {
            this.accountId = accountId
            externalId = data["id"].asText()
            externalPriceId = item["price"]["id"].asText()
            currency = data["currency_code"].asText()
            lockedUnitPriceMinor = unitPrice
        }
        val isNew = existing == null
        if (!isNew && unitPrice > r.lockedUnitPriceMinor) {
            // Paddle may only charge what was locked: the lock stays, and the customer and we hear of it.
            reportPriceAboveLock(accountId, r, unitPrice, data)
        } else {
            r.lockedUnitPriceMinor = minOf(r.lockedUnitPriceMinor, unitPrice)
        }
        r.externalCustomerId = data["customer_id"]?.asText() ?: r.externalCustomerId
        r.billingInterval = data["billing_cycle"]?.get("interval")?.asText() ?: r.billingInterval ?: "month"
        r.status = data["status"].asText()
        r.seats = item["quantity"]?.asInt() ?: r.seats ?: 1
        r.currentPeriodEnd = data["current_billing_period"]?.get("ends_at")?.asText()?.let(Instant::parse)
        r.cancelAt = scheduledCancel(data)
        r.canceledAt = data["canceled_at"]?.takeIf { !it.isNull }?.asText()?.let(Instant::parse)
        r.lastEventAt = occurred ?: r.lastEventAt
        r.store()
        if (isNew) funnel.event(accountId, Funnel.SUBSCRIPTION_STARTED, mapOf("interval" to r.billingInterval, "seats" to r.seats))
        if (isPaying(r)) reactivate(accountId) else lapseIfOverFree(accountId)
    }

    /**
     * Paddle reported a price per seat above the lock (decision record 0023). The lock stays on our
     * record. Each such price is recorded once per billing period, as a billing event the billing
     * page shows until it's refunded, and every admin of the account is emailed. The mail setup has
     * no operator address, so we hear of it through this error in the app's log. The difference is
     * refunded by hand, for now.
     */
    private fun reportPriceAboveLock(accountId: UUID, s: SubscriptionsRecord, reported: Long, data: JsonNode) {
        val periodEnd = data["current_billing_period"]?.get("ends_at")?.asText()?.let(Instant::parse) ?: s.currentPeriodEnd
        // The billing period Paddle's price is for, as this event says.
        val per = if ((data["billing_cycle"]?.get("interval")?.asText() ?: s.billingInterval) == "year") "year" else "month"
        val id = "$PRICE_ABOVE_LOCK:${s.externalId}:${periodEnd?.epochSecond ?: 0}:${s.currency}:$per:${s.lockedUnitPriceMinor}:$reported"
        log.error(
            "PRICE LOCK: Paddle reports {} {} per seat for subscription {} (account {}), above the locked {}. The lock stays; " +
                "refund the difference by hand, then set billing event {} to {}",
            reported, s.currency, s.externalId, accountId, s.lockedUnitPriceMinor, id, PRICE_ABOVE_LOCK_REFUNDED,
        )
        val fresh = dsl.insertInto(BILLING_EVENTS).set(BILLING_EVENTS.EVENT_ID, id).set(BILLING_EVENTS.EVENT_TYPE, PRICE_ABOVE_LOCK)
            .set(BILLING_EVENTS.ACCOUNT_ID, accountId).onConflictDoNothing().execute()
        if (fresh == 0) return
        val account = dsl.selectFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(accountId)).fetchOne() ?: return
        val model = mapOf(
            "account" to account.name,
            "reported" to money(reported, s.currency),
            "locked" to money(s.lockedUnitPriceMinor, s.currency),
            "per" to per,
            "link" to "${props.baseUrl}/settings/billing",
        )
        // Sent once the change is saved, like all mail; a webhook that rolls back emails nobody.
        dsl.select(MEMBERSHIPS.EMAIL, MEMBERSHIPS.NAME).from(MEMBERSHIPS)
            .where(MEMBERSHIPS.ACCOUNT_ID.eq(accountId)).and(MEMBERSHIPS.ROLE.eq("admin"))
            .and(MEMBERSHIPS.IS_ACTIVE.isTrue).and(MEMBERSHIPS.STATUS.eq("active"))
            .fetch().forEach {
                mailer.send("price-above-lock", it.value1(), java.util.Locale.forLanguageTag(account.locale), model + ("name" to it.value2()), arrayOf(account.name))
            }
    }

    private fun money(minor: Long, currency: String) = "$currency ${Money.fromMinor(minor, currency).toPlainString()}"

    /**
     * Deleting an account cancels its subscription at Paddle, so nobody keeps paying for an account
     * that's gone. Before, it renewed, and its next event could attach to an account imported with
     * the same id.
     */
    @org.springframework.context.event.EventListener
    fun onAccountPurging(e: AccountPurging) {
        val s = tx.system { dsl.selectFrom(SUBSCRIPTIONS).where(SUBSCRIPTIONS.ACCOUNT_ID.eq(e.accountId)).fetchOne() } ?: return
        if (!isPaying(s) || !settings.configured) return
        try {
            paddle.cancel(s.externalId)
            log.info("Deleting account {}: cancelled subscription {}", e.accountId, s.externalId)
        } catch (ex: PaddleException) {
            log.error("Deleting account {}: cancelling subscription {} failed ({}); cancel it in Paddle by hand", e.accountId, s.externalId, ex.message)
        }
    }

    /**
     * Keeps Paddle's seat count in step with the people who can sign in (the job, every 15
     * minutes): it lowers the count after someone is deactivated, and catches up on a seat Paddle
     * couldn't be told about at once. Open invitations aren't counted: nobody is billed for one
     * until it's accepted.
     */
    fun syncSeats(): Int {
        if (!settings.configured) return 0
        val subs = system { dsl.selectFrom(SUBSCRIPTIONS).where(SUBSCRIPTIONS.STATUS.`in`("trialing", "active", "past_due")).fetch() }
        return subs.count { sync(it) }
    }

    /**
     * Someone can sign in from now on (an invitation accepted, a person back). Only once that is
     * saved does Paddle hear, so the seat is billed from this moment, prorated, and never for a
     * change that was rolled back. If Paddle can't be reached, the job tries again; the person can
     * sign in either way.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onSeatTaken(e: SeatTaken) {
        if (!settings.configured) return
        try {
            val s = system { dsl.selectFrom(SUBSCRIPTIONS).where(SUBSCRIPTIONS.ACCOUNT_ID.eq(e.accountId)).fetchOne() } ?: return
            if (isPaying(s)) sync(s)
        } catch (ex: Exception) {
            // The change is saved already; the job catches up.
            log.error("Seat update for account {} failed; the seat sync will try again", e.accountId, ex)
        }
    }

    /**
     * Sets the subscription's seats at Paddle to the people who can sign in; true if it changed
     * them. Each database step is a short transaction of its own, so nothing stays locked while
     * Paddle answers. The new count is claimed before Paddle is asked, so two syncs at once (the
     * job and an accepted invitation) can't both send it.
     */
    private fun sync(s: SubscriptionsRecord): Boolean {
        val billed = system { dsl.select(SUBSCRIPTIONS.SEATS).from(SUBSCRIPTIONS).where(SUBSCRIPTIONS.ID.eq(s.id)).fetchOne()?.value1() } ?: return false
        val used = maxOf(1, system { seats.used(s.accountId) })
        if (used == billed) return false
        val claimed = system {
            dsl.update(SUBSCRIPTIONS).set(SUBSCRIPTIONS.SEATS, used).where(SUBSCRIPTIONS.ID.eq(s.id)).and(SUBSCRIPTIONS.SEATS.eq(billed)).execute()
        }
        if (claimed == 0) return false
        return try {
            paddle.updateQuantity(s.externalId, s.externalPriceId, used)
            true
        } catch (e: PaddleException) {
            // Not changed at Paddle: back to what Paddle bills, so the next sync sends it again.
            system { dsl.update(SUBSCRIPTIONS).set(SUBSCRIPTIONS.SEATS, billed).where(SUBSCRIPTIONS.ID.eq(s.id)).and(SUBSCRIPTIONS.SEATS.eq(used)).execute() }
            log.warn("Seat update for subscription {} failed: {}", s.externalId, e.message)
            false
        }
    }

    /**
     * Accounts whose subscription ended: read-only while more people can sign in than the free plan
     * holds (export still works), and active again once they're within it. The second half is the
     * backstop for a free plan made larger, or a deactivation that raced another change
     * (decision record 0021).
     */
    fun lapseDue(): Int {
        val ended = tx.system {
            dsl.select(SUBSCRIPTIONS.ACCOUNT_ID).from(SUBSCRIPTIONS)
                .where(SUBSCRIPTIONS.STATUS.`in`("canceled", "paused")).fetch(SUBSCRIPTIONS.ACCOUNT_ID)
        }
        return ended.count { tx.system { lapseIfOverFree(it) || reactivateIfWithinFree(it) } }
    }

    private fun lapseIfOverFree(accountId: UUID): Boolean {
        // Locked before counting, so a deactivation at the same moment counts after this, or before.
        val account = dsl.selectFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(accountId)).forUpdate().fetchOne() ?: return false
        if (account.status != "active" || seats.used(accountId) <= freePlan.seats) return false
        account.status = "lapsed"
        account.lapsedAt = Instant.now(clock)
        account.store()
        return true
    }

    /**
     * Someone was deactivated. A lapsed account that is back within the free plan works again at
     * once, in the same transaction as the deactivation (decision record 0021). Paddle's count is
     * left to the seat sync, as before; a lapsed account has no subscription paying for seats.
     */
    @org.springframework.context.event.EventListener
    fun onSeatFreed(e: SeatFreed) {
        reactivateIfWithinFree(e.accountId)
    }

    /**
     * Active again if a lapsed account is within the free plan. The account row is locked before
     * counting: two deactivations at the same moment would otherwise each count the other's person
     * as still there, and neither would make the account active again.
     */
    private fun reactivateIfWithinFree(accountId: UUID): Boolean {
        val account = dsl.selectFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(accountId)).forUpdate().fetchOne() ?: return false
        if (account.status != "lapsed" || seats.used(accountId) > freePlan.seats) return false
        return reactivate(accountId) > 0
    }

    private fun reactivate(accountId: UUID): Int =
        dsl.update(ACCOUNTS).set(ACCOUNTS.STATUS, "active").setNull(ACCOUNTS.LAPSED_AT)
            .where(ACCOUNTS.ID.eq(accountId)).and(ACCOUNTS.STATUS.eq("lapsed")).execute()

}

/**
 * Limits are kept at the moment you act (the Robin's Code, "We trust you too"; decision record
 * 0020). On the free plan, a person beyond it needs a Team subscription: refused with the Team
 * prices. On the Team plan, everyone who can sign in is billed, so giving someone sign-in access
 * adds a paid seat: refused with its price until an admin says yes to that same price. Nothing is
 * charged here; the seat is billed from the moment the person can sign in
 * (BillingService.onSeatTaken).
 */
@Component
@Primary
@CloudEditionOnly
class SubscriptionSeatGate(
    private val dsl: DSLContext,
    private val seats: SeatCounter,
    private val settings: PaddleSettings,
    private val prices: BillingPrices,
    private val freePlan: FreePlan,
) : SeatGate {
    override fun requireSeat(accountId: UUID, starts: SeatStart, confirmed: SeatPrice?, counted: Boolean) {
        // Without Paddle there is nothing to buy, so nothing to hold back.
        if (!settings.configured) return
        val s = dsl.selectFrom(SUBSCRIPTIONS).where(SUBSCRIPTIONS.ACCOUNT_ID.eq(accountId))
            .and(SUBSCRIPTIONS.STATUS.`in`("trialing", "active", "past_due")).fetchOne()
        if (s == null) {
            if (seats.claimed(accountId) + (if (counted) 0 else 1) > freePlan.seats) throw subscriptionRequired()
            return
        }
        // Back at once into a seat that's still paid for (someone deactivated in the last 15
        // minutes, before the seat sync lowered the count): nothing more to pay, nothing to ask.
        if (starts == SeatStart.NOW && seats.used(accountId) + 1 <= s.seats) return
        val price = SeatPrice(s.lockedUnitPriceMinor, s.currency, s.billingInterval)
        if (confirmed == price) return
        throw confirmationRequired(s, price, starts, stale = confirmed != null)
    }

    /**
     * Someone accepting an invitation after the subscription ended, when the free plan has no room
     * for them, is refused, and nothing changes: before, they got in and the account turned
     * read-only for everyone (decision record 0021). On the Team plan the yes was given when the
     * invitation went out, so joining asks nothing more. An account that never had a subscription
     * doesn't turn read-only, so it isn't refused either.
     */
    override fun requireSeatToJoin(accountId: UUID) {
        // One at a time for each account, so two people can't both take the last free seat.
        dsl.execute("select pg_advisory_xact_lock(hashtextextended(?, 7234004))", "join:$accountId")
        val s = dsl.selectFrom(SUBSCRIPTIONS).where(SUBSCRIPTIONS.ACCOUNT_ID.eq(accountId)).fetchOne() ?: return
        if (s.status in setOf("trialing", "active", "past_due")) return
        if (seats.used(accountId) + 1 <= freePlan.seats) return
        throw ConflictException(
            "invitation_needs_team_plan",
            "This invitation needs the workspace's Team plan, which isn't active right now. Ask the person who invited you.",
        )
    }

    private fun subscriptionRequired(): ApiException {
        val who = if (freePlan.seats == 1) "one person" else "${freePlan.seats} people"
        val currency = prices.currency
        return ApiException(
            HttpStatus.PAYMENT_REQUIRED, "subscription_required",
            "The free plan is for $who. To give more people sign-in access, start a Team subscription: " +
                "${money(prices.teamAnnualMonthlyMinor, currency)} per person per month paid yearly " +
                "(${money(prices.teamAnnualMonthlyMinor * 12, currency)} a year), or ${money(prices.teamMonthlyMinor, currency)} paid monthly.",
            details = mapOf(
                "free_plan_seats" to freePlan.seats,
                // The same list prices as the billing page (GET /api/v1/billing/subscription).
                "prices" to listOf(
                    mapOf("interval" to "year", "currency" to currency, "per_seat_per_month_minor" to prices.teamAnnualMonthlyMinor),
                    mapOf("interval" to "month", "currency" to currency, "per_seat_per_month_minor" to prices.teamMonthlyMinor),
                ),
            ),
        )
    }

    private fun confirmationRequired(s: SubscriptionsRecord, price: SeatPrice, starts: SeatStart, stale: Boolean): ApiException {
        // The subscription's own price per seat for each billing period, which Paddle charges. (VERIFY
        // with Paddle's sandbox that a yearly price's unit_price is the amount for the whole year.)
        val unit = money(price.unitPriceMinor, price.currency)
        val perPeriod = if (price.interval == "year") "$unit a year, paid yearly" else "$unit a month"
        val message = buildString {
            if (stale) append("That's not the price now. ")
            append(
                when (starts) {
                    SeatStart.WHEN_ACCEPTED -> "When they accept, you pay for them: $perPeriod. That day, Paddle charges for what's left of the current billing period, up to $unit. Nothing is charged before they accept."
                    SeatStart.NOW -> "Once they're back, you pay for them: $perPeriod. Today, Paddle charges for what's left of the current billing period, up to $unit."
                },
            )
            append(" To go ahead, send the request again with confirm_unit_price_minor=${price.unitPriceMinor}, confirm_currency=${price.currency} and confirm_interval=${price.interval}.")
        }
        return ConflictException(
            "seat_confirmation_required", message,
            details = mapOf(
                "unit_price_minor" to price.unitPriceMinor,
                "currency" to price.currency,
                "interval" to price.interval,
                "billing_starts" to (if (starts == SeatStart.NOW) "now" else "when_accepted"),
                "seats_billed" to s.seats,
                "current_period_end" to s.currentPeriodEnd?.toString(),
                "stale" to stale,
            ),
        )
    }

    private fun money(minor: Long, currency: String) = "$currency ${Money.fromMinor(minor, currency).toPlainString()}"
}

/** Paddle.js runs the checkout in the browser: its script and frames are allowed where billing is set up. (VERIFY the origins with Paddle.) */
@Component
@CloudEditionOnly
class PaddleCsp(private val settings: PaddleSettings) : com.honestrobin.time.platform.security.CspContributor {
    override fun sources() = if (!settings.configured) {
        com.honestrobin.time.platform.security.CspSources()
    } else {
        com.honestrobin.time.platform.security.CspSources(
            script = listOf("https://cdn.paddle.com"),
            frame = listOf("https://buy.paddle.com", "https://sandbox-buy.paddle.com"),
            connect = listOf("https://*.paddle.com"),
            img = listOf("https://*.paddle.com"),
        )
    }
}

@Configuration
@CloudEditionOnly
class BillingJobsConfig {
    @Bean
    fun billingTask(@org.springframework.context.annotation.Lazy billing: BillingService): RecurringTask<Void> =
        Tasks.recurring("billing-sync", FixedDelay.of(Duration.ofMinutes(15))).execute { _, _ ->
            billing.syncSeats()
            billing.lapseDue()
        }
}

@RestController
@CloudEditionOnly
@RequestMapping("/api/v1/billing")
@Tag(name = "billing", description = "Honest Robin Cloud subscription (cloud edition only)")
class BillingController(private val billing: BillingService) {
    @GetMapping("/subscription")
    fun subscription(): SubscriptionView = billing.view(Current.member())

    @PostMapping("/checkout")
    @Operation(summary = "Details for opening the Paddle checkout in the browser")
    fun checkout(@RequestBody body: CheckoutRequest): CheckoutView = billing.checkout(Current.member(), body)

    @PostMapping("/portal")
    @Operation(summary = "A link to Paddle's customer portal")
    fun portal(): PortalView = billing.portal(Current.member())

    @PostMapping("/cancellation")
    @Operation(summary = "Cancel the Team plan: at the end of the period that's paid for, or at once while a payment is past due (ends=now)")
    fun cancel(@RequestBody(required = false) body: CancelRequest?): SubscriptionView {
        billing.cancel(Current.member(), (body ?: CancelRequest()).ends)
        return billing.view(Current.member())
    }

    @DeleteMapping("/cancellation")
    @Operation(summary = "Keep the Team plan: take back a cancellation before it happens")
    fun keep(): SubscriptionView {
        billing.keep(Current.member())
        return billing.view(Current.member())
    }
}

@RestController
@CloudEditionOnly
class PaddleWebhookController(private val billing: BillingService) {
    @PostMapping("/webhooks/paddle")
    fun webhook(@RequestBody payload: String, @RequestHeader(name = "Paddle-Signature", required = false) signature: String?): ResponseEntity<Unit> =
        if (billing.webhook(payload, signature)) ResponseEntity.ok().build() else ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
}
