// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.support

import com.honestrobin.time.platform.mail.MailTransport
import com.honestrobin.time.platform.mail.OutgoingMail
import org.springframework.stereotype.Component
import java.util.concurrent.CopyOnWriteArrayList

@Component
class RecordingMailTransport : MailTransport {
    val sent = CopyOnWriteArrayList<OutgoingMail>()

    override fun send(mail: OutgoingMail) {
        sent += mail
    }

    fun to(email: String) = sent.filter { email in it.to }

    fun lastTo(email: String): OutgoingMail = to(email).lastOrNull() ?: error("No mail sent to $email; sent: ${sent.map { it.to to it.subject }}")

    /** Extracts the token from a `…#<token>` link in the last mail to [email]. */
    fun linkToken(email: String): String = Regex("#([A-Za-z0-9_-]{20,})").find(lastTo(email).text)?.groupValues?.get(1)
        ?: error("No link in mail to $email")
}
