// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.invoicing

import java.util.UUID

/** An invoice got its number and went out (sent, or marked as sent). Published inside the transaction. */
data class InvoiceIssued(val accountId: UUID, val invoiceId: UUID)

/** A payment was recorded on an invoice, by a person or by an online payment. Published inside the transaction. */
data class PaymentRecorded(val accountId: UUID, val invoiceId: UUID, val paymentId: UUID)
