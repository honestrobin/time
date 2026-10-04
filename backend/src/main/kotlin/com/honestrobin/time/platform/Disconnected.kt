// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform

/** What disconnecting an outside service (Stripe, QuickBooks, Xero, Storecove) did over there. */
data class Disconnected(
    /** Honest Robin's access at the provider has ended, or there was none to end. */
    val revoked: Boolean,
    /** What is still left at the provider, and how to remove it there; null when nothing is. */
    val note: String? = null,
)
