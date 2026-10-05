// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform

import com.honestrobin.time.HonestRobinApplication
import com.honestrobin.time.platform.security.CspContributor
import com.honestrobin.time.platform.security.contentSecurityPolicy
import com.honestrobin.time.support.TestDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import java.io.File

/**
 * "Every company that touches your data is listed" (the Robin's Code, "Your data's home is
 * Europe"). The list is docs/companies.md. This test collects every outside host the backend can
 * reach: the web addresses in its code and configuration, and the origins the browser may reach
 * under the Content-Security-Policy of the cloud edition, with every provider set up. Each host
 * must be on the list, written in backticks (a host or a domain above it), or in [NEVER_SEE_DATA]
 * with a reason. So a new provider can't be added without the public list changing too.
 *
 * It sees addresses written out in the code, as every provider's settings have them today. An
 * address put together at run time ("https://" + host) would slip past: keep provider hosts as
 * literal defaults in their settings.
 */
class CompanyListTest {

    @Test
    fun `every outside host the code can reach is on the public list of companies`() {
        val inCode = hostsInCode()
        val inCsp = hostsInCsp()
        assertThat(inCode).describedAs("No web addresses found in backend/src/main: is the scan looking in the right place?").isNotEmpty()
        assertThat(inCsp).describedAs("The cloud edition's Content-Security-Policy names no outside origin: are Paddle and PostHog set up below?").isNotEmpty()

        val listed = listedHosts()
        val missing = (inCode.keys + inCsp).filter { host -> host !in NEVER_SEE_DATA && listed.none { covers(it, host) } }.sorted()
        assertThat(missing).describedAs(
            "These hosts aren't on docs/companies.md: " +
                missing.joinToString("; ") { "$it (in ${inCode[it]?.joinToString() ?: "the Content-Security-Policy"})" } +
                ". Add the company behind each one, with its country, what it does, what it sees and, if it isn't European, " +
                "why we still use it, and write the host in backticks. The maintainer approves the list.",
        ).isEmpty()
    }

    @Test
    fun `every host kept off the list is still in the code`() {
        assertThat(NEVER_SEE_DATA.keys - hostsInCode().keys)
            .describedAs("No longer in the code, so remove them from NEVER_SEE_DATA")
            .isEmpty()
    }

    /** Each host in the backend's code and configuration, with the files that name it. */
    private fun hostsInCode(): Map<String, List<String>> {
        val code = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
        // Licence texts and the list of common passwords are left out: they're data, not addresses.
        val config = File("src/main/resources").walkTopDown().filter { it.isFile && it.extension in CONFIG_FILES }
        return (code + config)
            .flatMap { file -> URL.findAll(file.readText()).map { host(it.groupValues[1]) to file.path } }
            .filter { (host, _) -> host.isNotEmpty() }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, files) -> files.distinct() }
    }

    /** The outside origins in the cloud edition's Content-Security-Policy, with every contributor set up. */
    private fun hostsInCsp(): Set<String> {
        val context = SpringApplicationBuilder(HonestRobinApplication::class.java)
            .profiles("test")
            .run(
                "--honestrobin.edition=cloud",
                "--server.port=0",
                "--db-scheduler.enabled=false",
                "--spring.datasource.url=${TestDatabase.sharedUrl}",
                "--spring.datasource.username=${TestDatabase.username}",
                "--spring.datasource.password=${TestDatabase.password}",
                // Billing and analytics add origins only when set up. Their hosts stay at the defaults.
                "--honestrobin.posthog.api-key=phc_test",
                "--honestrobin.paddle.api-key=test",
                "--honestrobin.paddle.webhook-secret=test",
                "--honestrobin.paddle.team-monthly-price-id=pri_test",
            )
        try {
            val policy = contentSecurityPolicy(context.getBeanProvider(CspContributor::class.java).orderedStream().map { it.sources() }.toList())
            return URL.findAll(policy).map { host(it.groupValues[1]) }.filter { it.isNotEmpty() }.toSet()
        } finally {
            context.close()
        }
    }

    /** The hosts and domains written in backticks on the public list. */
    private fun listedHosts(): Set<String> {
        val list = File("../docs/companies.md")
        assertThat(list).describedAs("The public list of companies").exists()
        return LISTED.findAll(list.readText()).map { it.groupValues[1] }.toSet()
    }

    /** `stripe.com` on the list covers api.stripe.com, and `paddle.com` covers the origin *.paddle.com. */
    private fun covers(listed: String, host: String): Boolean {
        val h = host.removePrefix("*.")
        return h == listed || h.endsWith(".$listed")
    }

    private fun host(raw: String) = raw.lowercase().trimEnd('.')

    companion object {
        private val URL = Regex("""(?:https?|wss?)://([A-Za-z0-9*.-]+)""")
        private val LISTED = Regex("""`([a-z0-9-]+(?:\.[a-z0-9-]+)*\.[a-z]{2,})`""")
        private val CONFIG_FILES = setOf("yml", "yaml", "properties", "html", "xml", "json", "sql")

        /** Hosts in the code that never see customers' data, and why. Matched exactly, subdomains not included. */
        private val NEVER_SEE_DATA = mapOf(
            "localhost" to "This instance's own address, until HONESTROBIN_BASE_URL is set.",
            "www.w3.org" to "The SVG namespace in the two-factor QR code: a name, never fetched.",
            "www.thymeleaf.org" to "The template namespace in the email and invoice templates: a name, never fetched.",
            "www.gnu.org" to "A link to the AGPL in the API description, for readers to follow.",
            "github.com" to "Our public repository, named in the User-Agent we send to Harvest. Nothing is sent to GitHub.",
            "honestrobin.com" to "Our own website, linked from the cloud edition. No customer data goes there.",
        )
    }
}
