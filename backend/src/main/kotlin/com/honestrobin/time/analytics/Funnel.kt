// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.analytics

import com.honestrobin.time.db.Tables.ACCOUNT_MILESTONES
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/**
 * The product funnel (spec AT-6.5): signup, import started and verified, first timer, first
 * invoice sent, invoices paid online, subscription started. The account is the distinct id, so a
 * team's steps line up; no names or emails are sent. Events leave only after the transaction
 * commits, only from an account that shares how it uses Time ([UsageSharing], off until it turns
 * it on), and only the cloud edition has a real [Analytics]: self-hosted instances send nothing.
 * The firsts are recorded in the account's own data either way.
 */
@Component
class Funnel(private val analytics: Analytics, private val dsl: DSLContext, private val sharing: UsageSharing) {

    fun event(accountId: UUID, name: String, properties: Map<String, Any?> = emptyMap()) {
        if (!sharing.sharedBy(accountId)) return
        val send = { analytics.capture(name, accountId, properties + ("account_id" to accountId.toString())) }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = send()
            })
        } else {
            send()
        }
    }

    /** Records a first (once per account) and sends its event the first time only. */
    fun milestone(accountId: UUID, key: String, properties: Map<String, Any?> = emptyMap()) {
        val inserted = dsl.insertInto(ACCOUNT_MILESTONES)
            .set(ACCOUNT_MILESTONES.ACCOUNT_ID, accountId).set(ACCOUNT_MILESTONES.KEY, key)
            .onConflictDoNothing().execute()
        if (inserted > 0) event(accountId, key, properties)
    }

    companion object {
        const val SIGNUP = "signup"
        const val IMPORT_STARTED = "import_started"
        const val IMPORT_VERIFIED = "import_verified"
        const val FIRST_TIMER_STARTED = "first_timer_started"
        const val FIRST_INVOICE_SENT = "first_invoice_sent"
        const val INVOICE_PAID_ONLINE = "invoice_paid_online"
        const val SUBSCRIPTION_STARTED = "subscription_started"
    }
}
