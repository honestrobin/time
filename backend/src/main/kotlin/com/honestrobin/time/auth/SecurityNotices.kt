// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.auth

import com.honestrobin.time.db.tables.records.UsersRecord
import com.honestrobin.time.platform.HonestRobinProperties
import com.honestrobin.time.platform.mail.Mailer
import org.springframework.stereotype.Component
import java.util.Locale

/**
 * Emails a person about something that happened to how they sign in (`mail.security-notice.<event>`,
 * with [arg] as its {0}), so they notice what they didn't do themselves.
 */
@Component
class SecurityNotices(private val mailer: Mailer, private val props: HonestRobinProperties) {
    /** [evenIfRolledBack]: about a failed attempt, so it goes out although the request fails. */
    fun send(user: UsersRecord, event: String, arg: Any? = null, evenIfRolledBack: Boolean = false) {
        val model = mapOf("name" to user.name, "messageKey" to "mail.security-notice.$event", "arg" to (arg ?: ""), "link" to "${props.baseUrl}/settings/profile")
        val locale = Locale.forLanguageTag(user.locale)
        if (evenIfRolledBack) mailer.sendNow("security-notice", user.email, locale, model) else mailer.send("security-notice", user.email, locale, model)
    }
}
