// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform

import com.honestrobin.time.db.Tables.AUDIT_LOG
import com.honestrobin.time.platform.db.DbContext
import com.honestrobin.time.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AuditLogTest : IntegrationTest() {

    @Test
    fun `every mutation is audited with actor and diff`() {
        val c = signup()
        c.patch("/api/v1/account", mapOf("name" to "Renamed Ltd", "time_rounding_minutes" to 15)).expect(200)

        val log = c.get("/api/v1/audit_log", mapOf("entity_type" to "accounts")).expect(200)
        val update = log["entries"].first { it["action"].asText() == "accounts.update" }
        assertThat(update["actor_user_id"].asText()).isEqualTo(c.userId.toString())
        assertThat(update["actor_type"].asText()).isEqualTo("user")
        assertThat(update["diff"]["name"][0].asText()).isEqualTo("Acme Studio")
        assertThat(update["diff"]["name"][1].asText()).isEqualTo("Renamed Ltd")
        assertThat(update["diff"]["time_rounding_minutes"][1].asInt()).isEqualTo(15)
        assertThat(update["diff"].has("updated_at")).isFalse()
    }

    @Test
    fun `audit log is append-only for the application`() {
        val c = signup()
        val result = runCatching {
            DbContext.forAccount(c.accountId!!) { tx.run { dsl.deleteFrom(AUDIT_LOG).execute() } }
        }
        assertThat(result.exceptionOrNull()).hasRootCauseMessage("ERROR: permission denied for table audit_log")
    }
}
