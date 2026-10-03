// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.invoicing

import com.honestrobin.time.db.Tables.INVOICES
import com.honestrobin.time.db.Tables.INVOICE_SEQUENCES
import com.honestrobin.time.db.tables.records.InvoiceSequencesRecord
import com.honestrobin.time.db.tables.records.InvoicesRecord
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Invoice numbers (spec §5.5, AT-3.2). A number is taken from the account's default sequence when
 * an invoice is first sent or marked as sent, inside the sending transaction and with the sequence
 * row locked, so concurrent sends get consecutive numbers and a rolled-back send gives its number
 * back. Numbers are never reused: a void invoice keeps its number, and drafts never get one.
 */
@Component
class InvoiceNumbering(private val dsl: DSLContext) {

    fun defaultSequence(accountId: java.util.UUID, lock: Boolean = false): InvoiceSequencesRecord {
        val query = dsl.selectFrom(INVOICE_SEQUENCES).where(INVOICE_SEQUENCES.IS_DEFAULT.isTrue)
        (if (lock) query.forUpdate().fetchOne() else query.fetchOne())?.let { return it }
        // Read-only callers (the send dialog) get an unsaved default instead of creating one.
        if (!lock && TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            return dsl.newRecord(INVOICE_SEQUENCES).apply { this.accountId = accountId; name = "Default"; prefix = ""; nextNumber = 1; padding = 0; perYear = false }
        }
        dsl.insertInto(INVOICE_SEQUENCES).set(INVOICE_SEQUENCES.ACCOUNT_ID, accountId).set(INVOICE_SEQUENCES.NAME, "Default")
            .set(INVOICE_SEQUENCES.IS_DEFAULT, true).onConflictDoNothing().execute()
        return if (lock) query.forUpdate().fetchOne()!! else query.fetchOne()!!
    }

    fun assign(invoice: InvoicesRecord) {
        val sequence = defaultSequence(invoice.accountId, lock = true)
        val year = invoice.issueDate.year
        if (sequence.perYear && sequence.currentYear?.toInt() != year) {
            sequence.currentYear = year.toShort()
            sequence.nextNumber = 1
        }
        // Skip numbers already taken (e.g. by imported invoices) rather than fail the send.
        var counter = sequence.nextNumber
        var number = format(sequence, counter, year)
        var guard = 0
        while (dsl.fetchExists(INVOICES, INVOICES.NUMBER.eq(number).and(INVOICES.ID.ne(invoice.id)))) {
            counter++
            number = format(sequence, counter, year)
            if (++guard > 10_000) error("No free invoice number after $number")
        }
        sequence.nextNumber = counter + 1
        sequence.store()
        invoice.number = number
        invoice.sequenceId = sequence.id
    }

    /** What the next number will look like, for the settings page. */
    fun preview(sequence: InvoiceSequencesRecord, year: Int): String {
        val counter = if (sequence.perYear && sequence.currentYear?.toInt() != year) 1 else sequence.nextNumber
        return format(sequence, counter, year)
    }

    companion object {
        /** {YYYY} in the prefix becomes the year; the counter is zero-padded to [padding] digits. */
        fun format(sequence: InvoiceSequencesRecord, counter: Long, year: Int): String =
            sequence.prefix.replace("{YYYY}", year.toString()) + counter.toString().padStart(sequence.padding.toInt(), '0')
    }
}
