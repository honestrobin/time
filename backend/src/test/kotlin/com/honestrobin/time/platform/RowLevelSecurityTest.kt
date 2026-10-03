// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform

import com.honestrobin.time.db.Tables.ACCOUNTS
import com.honestrobin.time.db.Tables.MEMBERSHIPS
import com.honestrobin.time.platform.db.DbContext
import com.honestrobin.time.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Defence in depth (spec §3.4): even a query without an account filter only sees the current tenant. */
class RowLevelSecurityTest : IntegrationTest() {

    @Test
    fun `unfiltered queries only see the current tenant`() {
        val a = signup()
        val b = signup()

        val seenByA = DbContext.forAccount(a.accountId!!) { tx.run { dsl.selectFrom(MEMBERSHIPS).fetch() } }
        assertThat(seenByA.map { it.accountId }.toSet()).containsExactly(a.accountId)

        val accountsSeenByB = DbContext.forAccount(b.accountId!!) { tx.run { dsl.selectFrom(ACCOUNTS).fetch() } }
        assertThat(accountsSeenByB.map { it.id }).containsExactly(b.accountId)
    }

    @Test
    fun `without a tenant nothing is visible and writes into another tenant fail`() {
        val a = signup()
        val b = signup()
        assertThat(tx.run { dsl.fetchCount(MEMBERSHIPS) }).isZero()

        val attempted = runCatching {
            DbContext.forAccount(a.accountId!!) {
                tx.run {
                    dsl.insertInto(MEMBERSHIPS).set(MEMBERSHIPS.ACCOUNT_ID, b.accountId).set(MEMBERSHIPS.NAME, "Intruder")
                        .set(MEMBERSHIPS.EMAIL, "intruder@example.test").execute()
                }
            }
        }
        assertThat(attempted.exceptionOrNull()).hasStackTraceContaining("violates row-level security policy")
    }

    @Test
    fun `the application role is not a superuser`() {
        signup()
        val role = tx.run { dsl.fetchValue("select current_user") as String }
        assertThat(role).isEqualTo("honestrobin_app")
    }
}
