// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.importers

import com.honestrobin.time.db.Tables.IMPORT_JOBS
import com.honestrobin.time.db.Tables.TIME_ENTRIES
import com.honestrobin.time.importers.harvest.HarvestImporter
import com.honestrobin.time.support.HarvestFixture
import com.honestrobin.time.support.IntegrationTest
import com.honestrobin.time.support.MockHarvest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Spec §6, step 4: after the import, Harvest's changes keep coming over until the cutover. */
class HarvestSyncTest : IntegrationTest() {
    @Autowired lateinit var importer: HarvestImporter

    private fun entries(account: UUID) = tx.system { dsl.fetchCount(TIME_ENTRIES, TIME_ENTRIES.ACCOUNT_ID.eq(account)) }

    @Test
    fun `changes made in Harvest keep coming over until the cutover`() {
        val f = HarvestFixture.agency(seed = 21, people = 3, clients = 1, projectsPerClient = 2, weeks = 8)
        MockHarvest.reset(f)
        val admin = signup()
        val account = admin.accountId!!
        val today = LocalDate.now(clock)
        val start = mapOf("token" to MockHarvest.TOKEN, "account_id" to MockHarvest.ACCOUNT_ID)
        admin.post("/api/v1/imports/harvest", start + ("sync_until" to today.plusDays(45))).expectError(422, "validation_failed")

        val job = admin.post("/api/v1/imports/harvest", start + ("sync_until" to today.plusDays(7))).expect(201)
        assertThat(job["status"].asText()).isEqualTo("syncing")
        assertThat(job["last_synced_at"].isNull).isFalse()
        val id = job.id()
        val imported = entries(account)
        assertThat(tx.system { dsl.select(IMPORT_JOBS.TOKEN_ENCRYPTED).from(IMPORT_JOBS).where(IMPORT_JOBS.ID.eq(id)).fetchOne()!!.value1() }).isNotNull()

        // Nothing changed in Harvest: the sync asks only for changes, and finds none.
        val quiet = admin.post("/api/v1/imports/$id/sync").expect(200)
        assertThat(quiet["stats"]["last_sync"]["time_entries"].asInt()).isZero()
        assertThat(entries(account)).isEqualTo(imported)

        // In Harvest meanwhile: an entry corrected, one added, and one changed that is timing here.
        val changedAt = Instant.now().plusSeconds(1).toString()
        val corrected = f.timeEntries.firstOrNull { it["is_locked"] == false && it["is_running"] == false } ?: error("no entry to correct in ${f.timeEntries.size}")
        corrected["hours"] = BigDecimal("6.50")
        corrected["notes"] = "Corrected in Harvest"
        corrected["updated_at"] = changedAt
        f.timeEntries += HashMap(corrected).apply { put("id", 888_888L); put("notes", "Added in Harvest"); put("updated_at", changedAt) }
        val timing = f.timeEntries.firstOrNull { it !== corrected && it["is_locked"] == false && it["is_running"] == false && it["id"] != 888_888L }
            ?: error("no second entry in ${f.timeEntries.map { listOf(it["id"], it["is_locked"], it["is_running"]) }}")
        val timingId = tx.system {
            dsl.select(com.honestrobin.time.db.Tables.EXTERNAL_LINKS.ENTITY_ID).from(com.honestrobin.time.db.Tables.EXTERNAL_LINKS)
                .where(com.honestrobin.time.db.Tables.EXTERNAL_LINKS.ACCOUNT_ID.eq(account))
                .and(com.honestrobin.time.db.Tables.EXTERNAL_LINKS.ENTITY_TYPE.eq("time_entry"))
                .and(com.honestrobin.time.db.Tables.EXTERNAL_LINKS.EXTERNAL_ID.eq(timing["id"].toString())).fetchOne()!!.value1()
        }
        tx.system { dsl.update(TIME_ENTRIES).set(TIME_ENTRIES.TIMER_STARTED_AT, Instant.now()).set(TIME_ENTRIES.NOTES, "Timing here").where(TIME_ENTRIES.ID.eq(timingId)).execute() }
        timing["notes"] = "Changed in Harvest meanwhile"
        timing["updated_at"] = changedAt

        val synced = admin.post("/api/v1/imports/$id/sync").expect(200)
        assertThat(synced["stats"]["last_sync"]["time_entries"].asInt()).isEqualTo(3)
        assertThat(entries(account)).isEqualTo(imported + 1)
        tx.system {
            val seconds = dsl.select(TIME_ENTRIES.DURATION_SECONDS).from(TIME_ENTRIES).where(TIME_ENTRIES.ACCOUNT_ID.eq(account)).and(TIME_ENTRIES.NOTES.eq("Corrected in Harvest")).fetch(TIME_ENTRIES.DURATION_SECONDS)
            assertThat(seconds).containsExactly(23_400)
            assertThat(dsl.fetchExists(TIME_ENTRIES, TIME_ENTRIES.ACCOUNT_ID.eq(account).and(TIME_ENTRIES.NOTES.eq("Added in Harvest")))).isTrue()
            // The entry timing here was left alone, and the reason is listed.
            assertThat(dsl.select(TIME_ENTRIES.NOTES).from(TIME_ENTRIES).where(TIME_ENTRIES.ID.eq(timingId)).fetchOne()!!.value1()).isEqualTo("Timing here")
        }
        assertThat(admin.get("/api/v1/imports/$id/issues").expect(200).body.values().map { it["reason"].asText() }).anyMatch { it.contains("kept as it is here") }

        // The cutover: syncing stops and the token is forgotten.
        val stopped = admin.post("/api/v1/imports/$id/stop_sync").expect(200)
        assertThat(stopped["status"].asText()).isEqualTo("completed")
        assertThat(tx.system { dsl.select(IMPORT_JOBS.TOKEN_ENCRYPTED).from(IMPORT_JOBS).where(IMPORT_JOBS.ID.eq(id)).fetchOne()!!.value1() }).isNull()
        admin.post("/api/v1/imports/$id/sync").expectError(409, "not_syncing")
    }

    @Test
    fun `the sync never brings back someone deactivated here`() {
        // Second review, 5 October 2026: someone active in Harvest came back here with each sync,
        // and on Honest Robin Cloud that can add a paid seat nobody said yes to.
        val f = HarvestFixture.agency(seed = 23, people = 3, clients = 1, projectsPerClient = 1, weeks = 1)
        MockHarvest.reset(f)
        val admin = signup()
        val job = admin.post("/api/v1/imports/harvest", mapOf("token" to MockHarvest.TOKEN, "account_id" to MockHarvest.ACCOUNT_ID, "sync_until" to LocalDate.now(clock).plusDays(7))).expect(201)
        val harvestUser = f.users.last()
        val email = harvestUser["email"] as String
        val id = admin.get("/api/v1/people", mapOf("limit" to 500)).expect(200)["data"].values().first { it["email"].asText() == email }["id"].asText()
        admin.post("/api/v1/people/$id/invite").expect(200)
        admin.patch("/api/v1/people/$id", mapOf("is_active" to false)).expect(200)

        // In Harvest they're still active, and something else about them changed.
        harvestUser["weekly_capacity"] = 108_000
        harvestUser["updated_at"] = Instant.now().plusSeconds(1).toString()
        admin.post("/api/v1/imports/${job.id()}/sync").expect(200)

        val after = admin.get("/api/v1/people/$id").expect(200)
        assertThat(after["is_active"].asBoolean()).isFalse()
        assertThat(after["weekly_capacity_seconds"].asInt()).isEqualTo(108_000)
        assertThat(admin.get("/api/v1/imports/${job.id()}/issues").expect(200).body.values().map { it["reason"].asText() })
            .anyMatch { it.contains("active in Harvest but deactivated here") }
    }

    @Test
    fun `a sync window ends by itself on its last day`() {
        MockHarvest.reset(HarvestFixture.agency(seed = 22, people = 1, clients = 1, projectsPerClient = 1, weeks = 1))
        val admin = signup()
        val job = admin.post("/api/v1/imports/harvest", mapOf("token" to MockHarvest.TOKEN, "account_id" to MockHarvest.ACCOUNT_ID, "sync_until" to LocalDate.now(clock).plusDays(1))).expect(201)
        assertThat(job["status"].asText()).isEqualTo("syncing")
        tx.system { dsl.update(IMPORT_JOBS).set(IMPORT_JOBS.SYNC_UNTIL, Instant.now().minusSeconds(60)).where(IMPORT_JOBS.ID.eq(job.id())).execute() }
        importer.syncAll()
        val after = admin.get("/api/v1/imports/${job.id()}").expect(200)
        assertThat(after["status"].asText()).isEqualTo("completed")
        assertThat(tx.system { dsl.select(IMPORT_JOBS.TOKEN_ENCRYPTED).from(IMPORT_JOBS).where(IMPORT_JOBS.ID.eq(job.id())).fetchOne()!!.value1() }).isNull()
    }
}
