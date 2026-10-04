// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform

import com.honestrobin.time.platform.mail.logsWhole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Without SMTP, what goes to the log (security review, 4 October 2026). */
class MailLoggingTest {
    @Test
    fun `only the mails that get people in go to the log whole, and only on private instances`() {
        for (template in listOf("magic-link", "password-reset", "invite", "verify-email")) {
            assertThat(logsWhole(template, signInBodies = true)).isTrue()
            assertThat(logsWhole(template, signInBodies = false)).isFalse()
        }
        // Invoices and reminders go to clients: never into the log.
        for (template in listOf("invoice", "invoice-reminder", "invoice-overdue", "budget-alert", "security-notice", null)) {
            assertThat(logsWhole(template, signInBodies = true)).isFalse()
        }
    }
}
