// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.invoicing

import com.honestrobin.time.platform.mail.OutboundMail
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay
import com.honestrobin.time.accounts.AccountSettingsRepository
import com.honestrobin.time.db.Tables.ACCOUNTS
import com.honestrobin.time.db.Tables.CLIENTS
import com.honestrobin.time.db.Tables.CLIENT_CONTACTS
import com.honestrobin.time.db.Tables.INVOICES
import com.honestrobin.time.db.Tables.USERS
import com.honestrobin.time.db.tables.records.InvoicesRecord
import com.honestrobin.time.platform.db.DbContext
import com.honestrobin.time.platform.db.Tx
import com.honestrobin.time.platform.mail.MailAttachment
import com.honestrobin.time.platform.mail.Mailer
import com.honestrobin.time.platform.mail.OutgoingMail
import com.honestrobin.time.platform.security.Member
import com.honestrobin.time.platform.web.ConflictException
import com.honestrobin.time.platform.web.ValidationException
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

data class SendInvoiceInput(
    /** Recipients; defaults to the client's contacts marked as invoice recipients. */
    val to: List<String>? = null,
    val subject: String? = null,
    val message: String? = null,
    val attachPdf: Boolean = true,
    /** Sends a copy to the sender. Defaults to the account setting. */
    val bccMe: Boolean? = null,
)

/** Default recipients, subject and message for the send dialog. */
data class SendDefaults(val to: List<String>, val subject: String, val message: String, val bccMe: Boolean)

private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

/**
 * Sending invoices by email (spec §5.5): the PDF attached, a link to the public invoice page, and
 * optionally a copy to the sender. Numbering happens in the same transaction, and the email goes out
 * after it commits, so a failed send never consumes a number.
 */
@Service
class InvoiceSender(
    private val dsl: DSLContext,
    private val invoices: InvoiceService,
    private val numbering: InvoiceNumbering,
    private val pdf: InvoicePdf,
    private val einvoices: com.honestrobin.time.einvoice.EInvoiceService,
    private val mailer: Mailer,
    private val outbound: OutboundMail,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun defaults(m: Member, id: UUID): SendDefaults {
        invoices.requireAccess(m)
        val r = invoices.load(id)
        val account = dsl.selectFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(r.accountId)).fetchOne()!!
        val locale = Locale.forLanguageTag(account.locale)
        val (subject, message) = defaultTexts(r, account.legalName?.ifBlank { null } ?: account.name, locale, expectedNumber(r))
        return SendDefaults(recipients(r), subject, message, account.invoiceBccSender)
    }

    @Transactional
    fun send(m: Member, id: UUID, input: SendInvoiceInput): InvoiceView {
        m.requireWritable()
        invoices.requireAccess(m)
        val r = invoices.load(id)
        if (r.isReadOnly) throw ConflictException("read_only", "Imported invoices can't be sent from here")
        if (r.state in setOf("paid", "void")) throw ConflictException("closed", "A ${r.state} invoice can't be sent")
        if (!mailer.canDeliver) {
            throw ConflictException(
                "email_not_set_up",
                "This instance can't send email yet: its admin needs to set up a mail server (SMTP). Until then, mark the invoice as sent and send the PDF yourself.",
            )
        }
        val to = (input.to ?: recipients(r)).map(String::trim).filter(String::isNotEmpty).distinct()
        if (to.isEmpty()) throw ValidationException("to", "Add at least one recipient, or mark a client contact as receiving invoices")
        to.firstOrNull { !EMAIL.matches(it) }?.let { throw ValidationException("to", "$it is not an email address") }
        if (to.size > 20) throw ValidationException("to", "Send to at most 20 recipients")
        outbound.checkInvoiceSend(r.accountId, m.userId, to.size)

        val account = dsl.selectFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(r.accountId)).fetchOne()!!
        val sender = dsl.select(USERS.EMAIL).from(USERS).where(USERS.ID.eq(m.userId)).fetchOne()?.value1()
        val locale = Locale.forLanguageTag(account.locale)
        val seller = account.legalName?.ifBlank { null } ?: account.name
        // The dialog showed texts with the number this draft was expected to get. If another
        // invoice took that number meanwhile, untouched default texts follow the real one.
        val shown = defaultTexts(r, seller, locale, expectedNumber(r))

        invoices.issue(r)
        r.sentTo = to.toTypedArray()
        r.store()

        val (defaultSubject, defaultMessage) = defaultTexts(r, seller, locale, r.number)
        val subjectText = input.subject?.takeIf { it.isNotBlank() && it != shown.first }
        val messageText = input.message?.takeIf { it.isNotBlank() && it != shown.second }
        val link = invoices.publicUrl(r.publicToken)!!
        val (_, text, html) = mailer.render(
            "invoice", locale,
            mapOf("message" to (messageText ?: defaultMessage), "link" to link, "seller" to seller, "number" to r.number),
        )
        val attachments = if (input.attachPdf) listOf(MailAttachment(pdf.filename(r), "application/pdf", einvoices.invoicePdf(r))) else emptyList()
        val bcc = if ((input.bccMe ?: account.invoiceBccSender) && sender != null) listOf(sender) else emptyList()
        mailer.dispatch(OutgoingMail(to, subjectText ?: defaultSubject, text, html, bcc, sender, attachments, "invoice"))
        return invoices.view(invoices.load(id))
    }

    /** Reminders go out only when email does; otherwise they'd be recorded as sent but never arrive. */
    fun canEmail() = mailer.canDeliver

    fun recipients(r: InvoicesRecord): List<String> =
        dsl.select(CLIENT_CONTACTS.EMAIL).from(CLIENT_CONTACTS)
            .where(CLIENT_CONTACTS.CLIENT_ID.eq(r.clientId)).and(CLIENT_CONTACTS.IS_INVOICE_RECIPIENT.isTrue).and(CLIENT_CONTACTS.EMAIL.isNotNull)
            .fetch(CLIENT_CONTACTS.EMAIL).filter { it.isNotBlank() }

    /** The number a draft will get if it is sent now. */
    private fun expectedNumber(r: InvoicesRecord): String =
        r.number ?: numbering.preview(numbering.defaultSequence(r.accountId), r.issueDate.year)

    private fun defaultTexts(r: InvoicesRecord, seller: String, locale: Locale, number: String?): Pair<String, String> {
        val number = number ?: r.number ?: ""
        val client = dsl.select(CLIENTS.NAME).from(CLIENTS).where(CLIENTS.ID.eq(r.clientId)).fetchOne()!!.value1()
        val subject = mailer.message("mail.invoice.defaultSubject", locale, number, seller)
        val message = mailer.message("mail.invoice.defaultMessage", locale, client, number)
        return subject to message
    }

    /**
     * One reminder: called by [InvoiceReminderService] when a reminder is due. False when it has
     * to wait, because the account has reached its daily email limit.
     */
    fun remind(r: InvoicesRecord, overdueDays: Long): Boolean {
        val account = dsl.selectFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(r.accountId)).fetchOne()!!
        val locale = Locale.forLanguageTag(account.locale)
        val seller = account.legalName?.ifBlank { null } ?: account.name
        val link = invoices.publicUrl(r.publicToken) ?: return true
        val to = r.sentTo?.toList()?.takeIf { it.isNotEmpty() } ?: recipients(r)
        if (to.isEmpty()) return true
        if (!outbound.tryReminder(r.accountId, to.size)) return false
        val template = if (overdueDays > 0) "invoice-overdue" else "invoice-reminder"
        val (subject, text, html) = mailer.render(
            template, locale,
            mapOf("link" to link, "seller" to seller, "number" to r.number, "days" to kotlin.math.abs(overdueDays)),
            arrayOf(r.number, seller),
        )
        val attachments = listOf(MailAttachment(pdf.filename(r), "application/pdf", einvoices.invoicePdf(r)))
        mailer.dispatch(OutgoingMail(to, subject, text, html, emptyList(), null, attachments, template))
        return true
    }
}

/**
 * Payment reminders (spec §5.5, AT-3.5): on the days the account chose relative to the due date
 * (by default three days before, on the day, and a week after), in the account's time zone. Each
 * reminder goes once; a paid or void invoice gets none. If the job was down for some days, only
 * the most recent missed reminder is sent, not all of them at once.
 */
@Service
class InvoiceReminderService(
    private val dsl: DSLContext,
    private val tx: Tx,
    private val sender: InvoiceSender,
    private val settings: AccountSettingsRepository,
    private val outbound: OutboundMail,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun runDue(): Int {
        val accounts = tx.system {
            dsl.select(ACCOUNTS.ID, ACCOUNTS.INVOICE_REMINDER_DAYS).from(ACCOUNTS)
                .where(ACCOUNTS.INVOICE_REMINDERS_ENABLED.isTrue).and(ACCOUNTS.STATUS.eq("active")).fetch()
        }
        return accounts.sumOf { a ->
            runCatching { DbContext.forAccount(a.value1()) { tx.run { remindAccount(a.value1(), a.value2().map { it.toInt() }.toSet()) } } }
                .onFailure { log.error("Invoice reminders failed for account {}", a.value1(), it) }
                .getOrDefault(0)
        }
    }

    fun remindAccount(accountId: UUID, days: Set<Int>): Int {
        if (!outbound.canSend(accountId) || !sender.canEmail()) return 0
        val today = settings.get(accountId).today(clock)
        var sent = 0
        dsl.selectFrom(INVOICES)
            .where(INVOICES.STATE.`in`("sent", "open", "partially_paid")).and(INVOICES.DUE_MINOR.gt(0L)).and(INVOICES.DUE_DATE.isNotNull)
            .and(INVOICES.IS_READ_ONLY.isFalse).and(INVOICES.PUBLIC_TOKEN.isNotNull)
            .fetch().forEach { r ->
                val offset = ChronoUnit.DAYS.between(r.dueDate, today)
                val already = r.remindersSent.map { it.toInt() }.toSet()
                // The latest scheduled reminder that is due now or was missed, if not sent yet.
                val due = days.filter { it <= offset && it !in already }.maxOrNull() ?: return@forEach
                if (offset - due > 14) return@forEach // too old to send now
                if (!sender.remind(r, offset)) {
                    log.info("Reminder for invoice {} waits: the account has reached its daily email limit", r.id)
                    return@forEach
                }
                r.remindersSent = (already + days.filter { it <= due }).sorted().map { it }.toTypedArray()
                r.lastReminderAt = Instant.now(clock)
                r.store()
                sent++
            }
        return sent
    }
}

@Configuration
class InvoiceJobsConfig {
    @Bean
    fun invoiceReminderTask(reminders: InvoiceReminderService): RecurringTask<Void> =
        Tasks.recurring("invoice-reminders", FixedDelay.of(Duration.ofHours(1))).execute { _, _ -> reminders.runDue() }
}
