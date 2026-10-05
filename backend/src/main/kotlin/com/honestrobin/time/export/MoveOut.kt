// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.export

import com.honestrobin.time.db.Tables.ACCOUNTS
import com.honestrobin.time.db.Tables.API_TOKENS
import com.honestrobin.time.db.Tables.IMPORT_JOBS
import com.honestrobin.time.db.Tables.INTEGRATIONS
import com.honestrobin.time.db.Tables.INVOICES
import com.honestrobin.time.db.Tables.LOGIN_TOKENS
import com.honestrobin.time.db.Tables.MEMBERSHIPS
import com.honestrobin.time.platform.security.Member
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Something still connected to an account: a token, a signed-in device, an outside service, or
 * links the account's clients use. Moving out lists each one, with where it's ended.
 */
data class ConnectionView(
    /**
     * subscription (Honest Robin Cloud's Team plan, which Paddle bills); api_token; device (signed
     * in with a code, such as the browser extension); invitation (a link that still lets someone
     * join); stripe; qbo; xero; storecove (Peppol); harvest_sync; harvest_import (an unfinished
     * import that keeps its Harvest token); invoice_links; invoice_reminders.
     */
    val kind: String,
    /** A token's or device's id, to revoke it (DELETE /api/v1/account/api_tokens/{id}); an import's, to end it. */
    val id: UUID? = null,
    /** A token's or device's name, the Stripe account, the books in QuickBooks or Xero, the Harvest account. */
    val name: String? = null,
    /** Whose token or device it is, or who is invited. */
    val membershipId: UUID? = null,
    val person: String? = null,
    /** How many, for invoice links. */
    val count: Int? = null,
    /** connect (through Honest Robin) or api_key (your own key), for Stripe and Peppol. */
    val mode: String? = null,
    val lastUsedAt: Instant? = null,
    /**
     * When it stops by itself: a token's expiry, an invitation's, the end of a Harvest sync, the
     * last day of a subscription that's cancelled.
     */
    val endsAt: Instant? = null,
    /** The page in Time where an admin ends it; null where it ends only with the account. */
    val endIn: String? = null,
    /** A subscription's plan: team, the one paid plan. */
    val plan: String? = null,
    /** A subscription's status, as Paddle reports it: trialing, active or past_due. */
    val status: String? = null,
    /** How often a subscription is billed: month or year. */
    val interval: String? = null,
    /** When a subscription that runs on is billed next; null once it's cancelled, or while a payment is past due. */
    val renewsAt: Instant? = null,
)

/**
 * The account's subscription to Honest Robin Cloud, as something still connected to it. Honest
 * Robin Cloud's billing module answers from the subscription; everywhere else there is none. This
 * way the export module lists it without reading the plan itself (PromiseGuardsTest, "only the
 * number of seats depends on the plan"). Asked in the caller's transaction.
 */
fun interface SubscriptionConnection {
    /** The subscription while Paddle bills it (or will, once a trial ends); null when there is none. */
    fun of(accountId: UUID): ConnectionView?
}

@Component
class NoSubscription : SubscriptionConnection {
    override fun of(accountId: UUID): ConnectionView? = null
}

/** What moving out started: the export, which emails when it's ready, and what's still connected. */
data class MoveOutView(val export: AccountExportView, val connections: List<ConnectionView>)

/**
 * Everything still connected to an account ("You can always leave" in the Robin's Code), a
 * Honest Robin Cloud subscription included. Only reads: moving out changes nothing in the account.
 */
@Service
class ConnectionsService(private val dsl: DSLContext, private val subscription: SubscriptionConnection) {
    @Transactional(readOnly = true)
    fun list(m: Member): List<ConnectionView> {
        m.requireAdmin()
        return of(m.accountId)
    }

    /**
     * Read in the caller's transaction: a request's, or the export's snapshot, so the export's
     * README shows the same moment as its data.
     */
    fun of(accountId: UUID): List<ConnectionView> =
        listOfNotNull(subscription.of(accountId)) + tokens(accountId) + invitations(accountId) + services(accountId) + harvest(accountId) +
            invoiceLinks(accountId) + reminders(accountId)

    /** Tokens that work now: not expired, and of people who can sign in. */
    private fun tokens(accountId: UUID): List<ConnectionView> {
        val now = Instant.now()
        return dsl.select(API_TOKENS.ID, API_TOKENS.NAME, API_TOKENS.IDLE_EXPIRY_DAYS, API_TOKENS.LAST_USED_AT, API_TOKENS.EXPIRES_AT, MEMBERSHIPS.ID, MEMBERSHIPS.NAME)
            .from(API_TOKENS).join(MEMBERSHIPS).on(MEMBERSHIPS.ID.eq(API_TOKENS.MEMBERSHIP_ID))
            .where(API_TOKENS.ACCOUNT_ID.eq(accountId))
            .and(API_TOKENS.EXPIRES_AT.isNull.or(API_TOKENS.EXPIRES_AT.gt(now)))
            .and(MEMBERSHIPS.IS_ACTIVE.isTrue).and(MEMBERSHIPS.STATUS.eq("active"))
            .orderBy(MEMBERSHIPS.NAME, API_TOKENS.CREATED_AT)
            .fetch { r ->
                ConnectionView(
                    // A device's token expires after days without use (ApiTokenService.createForDevice).
                    kind = if (r[API_TOKENS.IDLE_EXPIRY_DAYS] != null) "device" else "api_token",
                    id = r[API_TOKENS.ID], name = r[API_TOKENS.NAME], membershipId = r[MEMBERSHIPS.ID], person = r[MEMBERSHIPS.NAME],
                    lastUsedAt = r[API_TOKENS.LAST_USED_AT], endsAt = r[API_TOKENS.EXPIRES_AT], endIn = MOVE_OUT,
                )
            }
    }

    /**
     * Invitations whose link still works: until it expires, accepting it lets someone in (and on
     * Honest Robin Cloud's Team plan, adds a seat). The same test as accepting (AuthService.inviteRow).
     */
    private fun invitations(accountId: UUID): List<ConnectionView> {
        val until = DSL.max(LOGIN_TOKENS.EXPIRES_AT)
        return dsl.select(MEMBERSHIPS.ID, MEMBERSHIPS.NAME, until)
            .from(LOGIN_TOKENS).join(MEMBERSHIPS).on(MEMBERSHIPS.ID.eq(LOGIN_TOKENS.MEMBERSHIP_ID))
            .where(MEMBERSHIPS.ACCOUNT_ID.eq(accountId))
            .and(LOGIN_TOKENS.PURPOSE.eq("invite")).and(LOGIN_TOKENS.USED_AT.isNull).and(LOGIN_TOKENS.EXPIRES_AT.gt(Instant.now()))
            .and(DSL.lower(LOGIN_TOKENS.EMAIL).eq(DSL.lower(MEMBERSHIPS.EMAIL)))
            .and(MEMBERSHIPS.IS_ACTIVE.isTrue).and(MEMBERSHIPS.STATUS.ne("active"))
            .groupBy(MEMBERSHIPS.ID, MEMBERSHIPS.NAME)
            .orderBy(MEMBERSHIPS.NAME)
            .fetch { r -> ConnectionView(kind = "invitation", name = r[MEMBERSHIPS.NAME], membershipId = r[MEMBERSHIPS.ID], person = r[MEMBERSHIPS.NAME], endsAt = r[until], endIn = MOVE_OUT) }
    }

    /** Stripe, QuickBooks, Xero and Storecove, where connected. */
    private fun services(accountId: UUID): List<ConnectionView> =
        dsl.selectFrom(INTEGRATIONS).where(INTEGRATIONS.ACCOUNT_ID.eq(accountId)).and(INTEGRATIONS.STATUS.eq("connected")).fetch()
            .sortedBy { SERVICES.indexOf(it.kind) }
            .map { ConnectionView(kind = it.kind, name = it.displayName, mode = it.mode, endIn = SERVICE_PAGES[it.kind]) }

    /** A Harvest sync that's running, or an unfinished import that keeps its Harvest token. */
    private fun harvest(accountId: UUID): List<ConnectionView> =
        dsl.selectFrom(IMPORT_JOBS).where(IMPORT_JOBS.ACCOUNT_ID.eq(accountId))
            .and(IMPORT_JOBS.STATUS.eq("syncing").or(IMPORT_JOBS.TOKEN_ENCRYPTED.isNotNull.and(IMPORT_JOBS.STATUS.notIn("completed", "cancelled"))))
            .orderBy(IMPORT_JOBS.CREATED_AT).fetch()
            .map {
                if (it.status == "syncing") {
                    ConnectionView(kind = "harvest_sync", id = it.id, name = it.externalAccountId, endsAt = it.syncUntil, endIn = "/settings/import#import-${it.id}")
                } else {
                    ConnectionView(kind = "harvest_import", id = it.id, name = it.externalAccountId, endIn = "/settings/import#import-${it.id}")
                }
            }

    /** Every issued invoice has a link that opens it for anyone who has it, emailed or not (drafts have none that work). */
    private fun invoiceLinks(accountId: UUID): List<ConnectionView> {
        val n = dsl.fetchCount(INVOICES, INVOICES.ACCOUNT_ID.eq(accountId).and(INVOICES.PUBLIC_TOKEN.isNotNull).and(INVOICES.STATE.ne("draft")))
        return if (n == 0) emptyList() else listOf(ConnectionView(kind = "invoice_links", count = n))
    }

    /** Reminders keep emailing the account's clients about unpaid invoices; they go out only while the account is active. */
    private fun reminders(accountId: UUID): List<ConnectionView> {
        val on = dsl.fetchExists(ACCOUNTS, ACCOUNTS.ID.eq(accountId).and(ACCOUNTS.INVOICE_REMINDERS_ENABLED.isTrue).and(ACCOUNTS.STATUS.eq("active")))
        return if (on) listOf(ConnectionView(kind = "invoice_reminders", endIn = "/settings/invoices")) else emptyList()
    }

    companion object {
        /** Where an admin revokes anyone's token or device and withdraws invitations. */
        private const val MOVE_OUT = "/settings/move-out"

        private val SERVICES = listOf("stripe", "qbo", "xero", "storecove")

        /** Where each outside service is disconnected. */
        private val SERVICE_PAGES = mapOf("stripe" to "/settings/payments", "qbo" to "/settings/accounting", "xero" to "/settings/accounting", "storecove" to "/settings/invoices")
    }
}

/**
 * The words for leaving in the export's README.txt. The Move out page says the same in the web
 * app's words (frontend/src/locales/en/moveout.json); change both together.
 */
object LeavingGuide {
    const val SELF_HOSTING_GUIDE = "https://github.com/honestrobin/time/blob/main/docs/self-host.md"

    fun whereToGo(): String = """
        |WHERE YOU CAN GO NEXT
        |
        |The same software, on your own server. Honest Robin Time is open source. Set it up with the
        |self-hosting guide: $SELF_HOSTING_GUIDE
        |Then sign in there and choose Settings > Account > Import an export, with this zip. The
        |account comes back as it is, with its history. You become its admin; invite everyone else
        |again from Team. Their history comes along; passwords don't.
        |
        |Another tool. The spreadsheets in csv/ open in any spreadsheet app, and most time tracking,
        |invoicing and accounting tools can import them:
        |  time-entries.csv  every time entry: date, client, project, task, person, notes, hours,
        |                    rates and amounts
        |  expenses.csv      every expense: date, project, category, person, notes and amount
        |  invoices.csv      every invoice: number, client, state, dates, totals and what's paid
        |  clients.csv, contacts.csv, projects.csv, tasks.csv, people.csv
        |They are UTF-8 text, comma-separated, with a header row. Dates are written YYYY-MM-DD,
        |hours as decimal numbers, amounts as plain numbers. Invoices are also in invoices/ as PDFs,
        |and as XRechnung or Peppol BIS files where an invoice has what those need.
        |
    """.trimMargin()

    fun stillConnected(connections: List<ConnectionView>, at: Instant): String = buildString {
        appendLine("STILL CONNECTED, ON ${LocalDate.ofInstant(at, ZoneOffset.UTC)} (UTC)")
        appendLine()
        if (connections.isEmpty()) {
            appendLine("Nothing: no tokens, devices, invitations, outside services or invoice links.")
        }
        connections.forEach { c ->
            appendLine("- ${what(c)}")
            appendLine("  ${how(c)}")
        }
        appendLine()
        appendLine("People in your team who sign in aren't listed: deactivate them under Team.")
        appendLine()
        appendLine("While the account is read-only (lapsed, or waiting to be deleted), an admin can still end")
        appendLine("each of these, except invoice links, which end only when the account is deleted. A lapsed")
        appendLine("account can also deactivate people under Team; one waiting to be deleted can't until the")
        appendLine("deletion is cancelled. Deleting the account ends our access to all of them.")
    }

    /** Cancelling is listed only for a subscription that runs on: one already cancelled ends by itself. */
    fun cancellingAndDeleting(connections: List<ConnectionView>): String = buildString {
        appendLine("CANCELLING AND DELETING")
        appendLine()
        appendLine("Making this export changed nothing in the account: it works as it did before. Cancelling and")
        appendLine("deleting are separate steps, and you choose them:")
        connections.firstOrNull { it.kind == "subscription" && it.endsAt == null }?.let { s ->
            appendLine("- Cancel the Team plan of Honest Robin Cloud under Settings > Billing > Cancel the Team plan.")
            appendLine("  ${cancelEnds(s)}")
        }
        appendLine("- Delete the account under Settings > Account > Delete this account. After 14 days the account")
        appendLine("  and everything in it are deleted for good, a subscription is cancelled, and our access to")
        appendLine("  Stripe, QuickBooks, Xero and Storecove ends. Until then it's read-only, you can still")
        appendLine("  export, and any admin can cancel.")
    }

    /** When cancelling ends a subscription: the request asks Paddle for the end of the current period, or now while a payment is past due. */
    private fun cancelEnds(c: ConnectionView): String = when (c.status) {
        "past_due" -> "While a payment is past due, that ends it at once."
        "trialing" -> "It ends at the end of the current billing period."
        else -> "It ends with the period you've paid for."
    }

    private fun day(at: Instant?) = at?.let { LocalDate.ofInstant(it, ZoneOffset.UTC).toString() }

    private fun what(c: ConnectionView): String = when (c.kind) {
        // Team is the one paid plan: the database refuses any other.
        "subscription" -> "Honest Robin Cloud subscription: the Team plan, paid ${if (c.interval == "year") "yearly" else "monthly"}. " + when {
            c.endsAt != null -> "Cancelled: it ends on ${day(c.endsAt)}."
            c.status == "past_due" -> "A payment is past due."
            c.status == "trialing" -> "On trial" + (day(c.renewsAt)?.let { "; the current billing period ends on $it." } ?: ".")
            else -> "Active." + (day(c.renewsAt)?.let { " It renews on $it." } ?: "")
        }
        "api_token" -> "API token \"${c.name}\" of ${c.person}" + (day(c.lastUsedAt)?.let { ", last used $it" } ?: ", never used") +
            (day(c.endsAt)?.let { ", expires $it" } ?: "") + "."
        "device" -> "Device \"${c.name}\", signed in as ${c.person}" + (day(c.lastUsedAt)?.let { ", last used $it" } ?: "") + "."
        "stripe" -> "Stripe: your clients pay invoices online into ${c.name ?: "your Stripe account"}."
        "qbo" -> "QuickBooks Online: invoices and payments go to ${c.name ?: "your books"}."
        "xero" -> "Xero: invoices and payments go to ${c.name ?: "your books"}."
        "storecove" -> "Peppol: e-invoices go out through Storecove, " + (if (c.mode == "connect") "on Honest Robin's contract." else "with your own Storecove API key.")
        "harvest_sync" -> "Harvest sync: changes in Harvest account ${c.name} come over until ${day(c.endsAt)}."
        "harvest_import" -> "Harvest import: an import from Harvest account ${c.name} hasn't finished, and we keep its token so it can go on."
        "invitation" -> "Invitation for ${c.person}: its link lets them join the account until ${day(c.endsAt)}."
        "invoice_links" -> "Invoice links: ${c.count} issued ${if (c.count == 1) "invoice has a link" else "invoices each have a link"} that opens it for anyone who has it, such as your client."
        "invoice_reminders" -> "Invoice reminders: we email your clients about unpaid invoices on the days you chose."
        else -> c.kind
    }

    private fun how(c: ConnectionView): String = when (c.kind) {
        "subscription" -> if (c.endsAt != null) {
            "Nothing to do: it ends by itself on ${day(c.endsAt)}. Until then, Settings > Billing > Keep the Team plan takes it back."
        } else {
            "End it: Settings > Billing > Cancel the Team plan, in two clicks. ${cancelEnds(c)} " +
                "Deleting the account cancels it too, on the day the account is deleted for good, 14 days after the deletion is asked for."
        }
        "api_token", "device" -> "End it: an admin revokes it under Settings > Account > Move out, or ${c.person} revokes it " +
            "under Profile > Personal access tokens." + (if (c.kind == "device") " It also stops after 90 days without use." else "")
        "stripe" -> "End it: Settings > Online payments > Disconnect Stripe" +
            (if (c.mode == "connect") ", which ends our access at Stripe. Or remove Honest Robin from the connected platforms in Stripe."
            else ". Then roll the key and delete the webhook endpoint in Stripe: the key works there until you do.")
        "qbo" -> "End it: Settings > Accounting > Disconnect, which ends our access to your books. Or disconnect Honest Robin in QuickBooks."
        "xero" -> "End it: Settings > Accounting > Disconnect, which ends our access to your books. Or disconnect Honest Robin in Xero."
        "storecove" -> "End it: Settings > Invoice settings > Disconnect, which removes your Peppol ID from Storecove." +
            (if (c.mode == "connect") "" else " Then delete the API key in Storecove.")
        "harvest_sync" -> "End it: Settings > Import from Harvest > Stop syncing and switch over. Then delete the personal access token in Harvest."
        "harvest_import" -> "End it: Settings > Import from Harvest > Cancel import, which forgets the token. Then delete the personal access token in Harvest."
        "invitation" -> "End it: an admin withdraws it under Settings > Account > Move out, and the link stops working. It also stops by itself on ${day(c.endsAt)}."
        "invoice_links" -> "They work while the account exists and stop for good when it's deleted. A single link can't be turned off yet."
        "invoice_reminders" -> "End them: Settings > Invoice settings, turn off reminders."
        else -> ""
    }
}
