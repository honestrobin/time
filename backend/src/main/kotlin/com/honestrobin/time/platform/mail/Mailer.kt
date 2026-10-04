// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.mail

import com.honestrobin.time.platform.forMessage
import com.honestrobin.time.platform.HonestRobinProperties
import jakarta.mail.internet.InternetAddress
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.MessageSource
import org.springframework.core.io.ByteArrayResource
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.util.Locale

data class MailAttachment(val filename: String, val contentType: String, val bytes: ByteArray)

data class OutgoingMail(
    val to: List<String>,
    val subject: String,
    val text: String,
    val html: String?,
    val bcc: List<String> = emptyList(),
    val replyTo: String? = null,
    val attachments: List<MailAttachment> = emptyList(),
    val template: String? = null,
)

fun interface MailTransport {
    fun send(mail: OutgoingMail)
}

/**
 * Renders `templates/mail/<name>.html` + `.txt` with the `mail.<name>.subject` message and sends
 * after the surrounding transaction commits (so rolled-back work never emails anyone).
 */
@Component
class Mailer(
    private val htmlEngine: SpringTemplateEngine,
    private val messages: MessageSource,
    private val props: HonestRobinProperties,
    private val transports: ObjectProvider<MailTransport>,
    private val javaMailSender: ObjectProvider<JavaMailSender>,
    // Spring creates a JavaMailSender whenever the property exists, even empty (pointing at localhost).
    @Value("\${spring.mail.host:}") private val smtpHost: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val textEngine = SpringTemplateEngine().apply {
        setTemplateEngineMessageSource(messages)
        addTemplateResolver(
            ClassLoaderTemplateResolver().apply {
                prefix = "templates/"
                suffix = ".txt"
                templateMode = TemplateMode.TEXT
                characterEncoding = "UTF-8"
                isCacheable = true
            },
        )
    }

    fun message(key: String, locale: Locale, vararg args: Any?): String = messages.getMessage(key, args.forMessage(), locale)

    fun render(template: String, locale: Locale, model: Map<String, Any?>, args: Array<Any?> = emptyArray()): Triple<String, String, String> {
        val ctx = Context(locale, model + ("baseUrl" to props.baseUrl))
        val subject = messages.getMessage("mail.$template.subject", args.forMessage(), locale)
        return Triple(subject, textEngine.process("mail/$template", ctx), htmlEngine.process("mail/$template", ctx))
    }

    fun send(
        template: String,
        to: String,
        locale: Locale,
        model: Map<String, Any?>,
        subjectArgs: Array<Any?> = emptyArray(),
        bcc: List<String> = emptyList(),
        replyTo: String? = null,
        attachments: List<MailAttachment> = emptyList(),
    ) {
        val (subject, text, html) = render(template, locale, model, subjectArgs)
        dispatch(OutgoingMail(listOf(to), subject, text, html, bcc, replyTo, attachments, template))
    }

    /**
     * Sends at once, even if the surrounding transaction rolls back: for notices about a failed
     * attempt, where the request fails but the person must still hear about it.
     */
    fun sendNow(template: String, to: String, locale: Locale, model: Map<String, Any?>) {
        val (subject, text, html) = render(template, locale, model)
        deliver(OutgoingMail(listOf(to), subject, text, html, template = template))
    }

    fun dispatch(mail: OutgoingMail) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = deliver(mail)
            })
        } else {
            deliver(mail)
        }
    }

    private fun deliver(mail: OutgoingMail) {
        try {
            transport().send(mail)
        } catch (e: Exception) {
            log.error("Failed to send '{}' mail to {}", mail.template, mail.to, e)
        }
    }

    /** Whether email actually goes out: false without SMTP, when mail is only written to the log. */
    val canDeliver: Boolean get() = deliveringTransport() != null

    private fun deliveringTransport(): MailTransport? =
        transports.getIfAvailable() ?: javaMailSender.getIfAvailable()?.takeIf { smtpHost.isNotBlank() }?.let { SmtpTransport(it, props.mail.from) }

    private fun transport(): MailTransport = deliveringTransport()
        ?: if (props.signupMode == HonestRobinProperties.SignupMode.OPEN) LoggingTransport.withoutBody else LoggingTransport.withBody

    private class SmtpTransport(private val sender: JavaMailSender, private val from: String) : MailTransport {
        override fun send(mail: OutgoingMail) {
            val message = sender.createMimeMessage()
            val helper = MimeMessageHelper(message, true, "UTF-8")
            helper.setFrom(InternetAddress(from))
            helper.setTo(mail.to.toTypedArray())
            if (mail.bcc.isNotEmpty()) helper.setBcc(mail.bcc.toTypedArray())
            mail.replyTo?.let { helper.setReplyTo(it) }
            helper.setSubject(mail.subject)
            if (mail.html != null) helper.setText(mail.text, mail.html) else helper.setText(mail.text)
            mail.attachments.forEach { helper.addAttachment(it.filename, ByteArrayResource(it.bytes), it.contentType) }
            sender.send(message)
        }
    }

    /**
     * Used when no SMTP server is configured. On a private instance, the mails that get people
     * into it (sign-in links, password resets, invitations, email confirmations) go to the log
     * whole, so its owner can still sign in and invite. Everything else, invoices to clients above
     * all, is only noted as not sent: no addresses, no text. Where anyone can sign up, nothing
     * goes to the log whole.
     */
    private class LoggingTransport(private val signInBodies: Boolean) : MailTransport {
        private val log = LoggerFactory.getLogger("com.honestrobin.time.mail")

        override fun send(mail: OutgoingMail) {
            if (logsWhole(mail.template, signInBodies)) {
                log.info("No SMTP configured; mail to {}:\nSubject: {}\n\n{}", mail.to, mail.subject, mail.text)
            } else {
                log.warn("No SMTP configured; a '{}' email to {} recipient(s) was not sent", mail.template, mail.to.size + mail.bcc.size)
            }
        }

        companion object {
            val withBody = LoggingTransport(true)
            val withoutBody = LoggingTransport(false)
        }
    }
}

/** The mails that get people into an instance: without SMTP its owner needs them from the log. */
internal val SIGN_IN_TEMPLATES = setOf("magic-link", "password-reset", "invite", "verify-email")

/** Whether a mail goes to the log whole when there's no SMTP (see LoggingTransport). */
internal fun logsWhole(template: String?, signInBodies: Boolean) = signInBodies && template in SIGN_IN_TEMPLATES
