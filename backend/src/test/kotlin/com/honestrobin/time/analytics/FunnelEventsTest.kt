// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.analytics

import com.honestrobin.time.db.Tables.ACCOUNT_MILESTONES
import com.honestrobin.time.payments.StripeClient
import com.honestrobin.time.platform.HonestRobinProperties
import com.honestrobin.time.platform.edition.Edition
import com.honestrobin.time.support.HarvestFixture
import com.honestrobin.time.support.IntegrationTest
import com.honestrobin.time.support.MockHarvest
import com.honestrobin.time.support.MockPostHog
import com.honestrobin.time.support.MockStripe
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import java.time.Instant
import java.util.UUID

/**
 * AT-6.5, as decision record 0028 changed it: the steps an account reaches are recorded in its own
 * data, and sent nowhere until it chooses to share how it uses Time, which no account can yet.
 */
class FunnelEventsTest : IntegrationTest() {
    @Autowired lateinit var props: HonestRobinProperties

    @Test
    fun `the steps an account reaches are kept in its data and sent nowhere until it chooses to share them`() {
        val admin = signup(name = "Wiremu Owner", accountName = "Tui Design")
        val account = admin.accountId!!

        // Import from Harvest (runs inline in tests) and verify.
        MockHarvest.reset(HarvestFixture.agency(seed = 31, people = 2, clients = 1, projectsPerClient = 1, weeks = 2))
        admin.post("/api/v1/imports/harvest", mapOf("token" to MockHarvest.TOKEN, "account_id" to MockHarvest.ACCOUNT_ID)).expect(201)

        // Two timers: only the first counts. Imported time doesn't make it a first.
        val task = createTask(admin, "Funnel work")
        val project = createProject(admin, taskIds = listOf(task)).id()
        val today = java.time.LocalDate.now(clock.withZone(java.time.ZoneId.of("Europe/Zagreb"))).toString()
        val first = admin.post("/api/v1/time_entries", mapOf("project_id" to project, "task_id" to task, "spent_date" to today)).expect(201).id()
        admin.post("/api/v1/time_entries/$first/stop").expect(200)
        admin.post("/api/v1/time_entries/$first/start").expect(200)

        // Two invoices sent; one paid online through Stripe.
        admin.post("/api/v1/payments/stripe/key", mapOf("secret_key" to MockStripe.ACCOUNT_KEY, "webhook_secret" to "whsec_funnel")).expect(200)
        val client = createClient(admin, "Kea Outdoor", "NZD")
        val invoices = (1..2).map {
            val id = admin.post("/api/v1/invoices", mapOf("client_id" to client, "lines" to listOf(mapOf("description" to "Work", "quantity" to 1, "unit_price" to 50_000)))).expect(201).id()
            admin.post("/api/v1/invoices/$id/mark_sent").expect(200)
            id
        }
        val payload = """{"id":"evt_${UUID.randomUUID()}","type":"checkout.session.completed","created":${Instant.now().epochSecond},"livemode":false,
            "data":{"object":{"payment_status":"paid","amount_total":50000,"currency":"nzd","payment_intent":"pi_funnel","metadata":{"invoice_id":"${invoices[0]}"}}}}"""
        val t = Instant.now().epochSecond
        client().request(HttpMethod.POST, "/webhooks/stripe/$account", payload, headers = mapOf("Stripe-Signature" to "t=$t,v1=${StripeClient.sign("whsec_funnel", t, payload)}")).expect(200)

        // Firsts are recorded in both editions.
        val milestones = tx.system { dsl.select(ACCOUNT_MILESTONES.KEY).from(ACCOUNT_MILESTONES).where(ACCOUNT_MILESTONES.ACCOUNT_ID.eq(account)).fetch(ACCOUNT_MILESTONES.KEY) }
        assertThat(milestones).containsExactlyInAnyOrder("first_timer_started", "first_invoice_sent")

        // Nothing leaves, in either edition: no account has chosen to share how it uses Time.
        Thread.sleep(500)
        assertThat(MockPostHog.forAccount(account)).isEmpty()
        assertThat(MockPostHog.events.joinToString { it.toString() }).doesNotContain(account.toString())
    }

    @Test
    fun `the web app sends page views in the cloud edition only, and only to PostHog`() {
        val res = client().get("/api/v1/auth/config").expect(200)
        val csp = client().get("/").headers["Content-Security-Policy"]?.firstOrNull().orEmpty()
        if (props.edition == Edition.CLOUD) {
            assertThat(res["analytics"]["posthog_key"].asText()).isEqualTo(MockPostHog.API_KEY)
            assertThat(res["analytics"]["posthog_host"].asText()).isEqualTo(MockPostHog.baseUrl)
            assertThat(csp.substringAfter("connect-src").substringBefore(";")).contains(MockPostHog.baseUrl)
        } else {
            assertThat(res["analytics"].isNull).isTrue()
            assertThat(csp).doesNotContain(MockPostHog.baseUrl)
        }
    }
}

