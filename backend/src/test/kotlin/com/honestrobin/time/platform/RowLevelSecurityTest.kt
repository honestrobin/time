// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform

import com.honestrobin.time.db.Tables.ACCOUNTS
import com.honestrobin.time.db.Tables.MEMBERSHIPS
import com.honestrobin.time.HonestRobinApplication
import com.honestrobin.time.platform.db.DbContext
import com.honestrobin.time.platform.db.QueryOutsideTransaction
import com.honestrobin.time.platform.db.RowLevelSecurityCheck
import com.honestrobin.time.platform.db.RowLevelSecurityNotEffective
import com.honestrobin.time.platform.db.TenantAwareTransactionManager
import com.honestrobin.time.support.IntegrationTest
import com.honestrobin.time.support.TestDatabase
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.diagnostics.FailureAnalyzedException
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/** Defence in depth (spec §3.4): even a query without an account filter only sees the current tenant. */
class RowLevelSecurityTest : IntegrationTest() {
    @Autowired
    lateinit var dataSource: DataSource

    @Autowired
    lateinit var transactions: PlatformTransactionManager

    /** Outside the app, as the tests' database user (a superuser): roles are made and dropped this way. */
    private fun asDatabaseUser(sql: String) = dataSource.connection.use { con -> con.createStatement().use { it.execute(sql) } }

    private fun assertRefused(query: () -> Any?) {
        val failure = runCatching { query() }.exceptionOrNull()
        assertThat(failure).describedAs("a query that ran outside a transaction").isNotNull()
        assertThat(generateSequence(failure) { it.cause }.toList()).anySatisfy { assertThat(it).isInstanceOf(QueryOutsideTransaction::class.java) }
    }

    /**
     * Outside a transaction there's no switch to the app's role and no account, so a query would
     * run as the database user, a superuser here as in the Compose setup, and row-level security
     * wouldn't apply. The app's queries are refused there, before they reach the database.
     */
    @Test
    fun `a query outside a transaction is refused`() {
        val a = signup()
        val account = a.accountId!!

        // No transaction, whatever the context says.
        assertRefused { dsl.fetchCount(MEMBERSHIPS) }
        assertRefused { DbContext.forAccount(account) { dsl.selectFrom(MEMBERSHIPS).fetch() } }
        assertRefused { DbContext.system { dsl.fetchValue("select current_user") } }
        // A scope that joins a transaction if there is one, and runs without one if not.
        val supports = TransactionTemplate(transactions).apply { propagationBehavior = TransactionDefinition.PROPAGATION_SUPPORTS }
        assertRefused { supports.execute { dsl.fetchCount(MEMBERSHIPS) } }
        // After the commit, while Spring still holds the transaction's connection for its callbacks:
        // refused on its own, and in tx.run too, which joins the transaction that has just ended.
        // A transaction of its own (REQUIRES_NEW, as BillingService.onSeatTaken uses) runs as usual.
        val independently = TransactionTemplate(transactions).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }
        var afterCommit: Throwable? = null
        var joined: Throwable? = null
        var ownTransaction: Any? = null
        DbContext.forAccount(account) {
            tx.run {
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() {
                        afterCommit = runCatching { dsl.fetchCount(MEMBERSHIPS) }.exceptionOrNull() ?: AssertionError("ran after the commit")
                        joined = runCatching { tx.run { dsl.fetchCount(MEMBERSHIPS) } }.exceptionOrNull() ?: AssertionError("ran in the ended transaction")
                        ownTransaction = independently.execute { dsl.fetchValue("select current_user") }
                    }
                })
            }
        }
        assertThat(generateSequence(afterCommit) { it.cause }.toList()).anySatisfy { assertThat(it).isInstanceOf(QueryOutsideTransaction::class.java) }
        assertThat(generateSequence(joined) { it.cause }.toList()).anySatisfy { assertThat(it).isInstanceOf(QueryOutsideTransaction::class.java) }
        assertThat(ownTransaction).isEqualTo("honestrobin_app")
        // After a rollback, the same.
        var afterRollback: Throwable? = null
        runCatching {
            tx.run<Unit> {
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCompletion(status: Int) {
                        afterRollback = runCatching { dsl.fetchCount(MEMBERSHIPS) }.exceptionOrNull() ?: AssertionError("ran after the rollback")
                    }
                })
                throw IllegalStateException("roll back")
            }
        }
        assertThat(generateSequence(afterRollback) { it.cause }.toList()).anySatisfy { assertThat(it).isInstanceOf(QueryOutsideTransaction::class.java) }

        // In a transaction, the same queries run as the app's role, and see only the account's rows.
        assertThat(DbContext.forAccount(account) { tx.run { dsl.fetchValue("select current_user") } }).isEqualTo("honestrobin_app")
        assertThat(DbContext.forAccount(account) { tx.run { dsl.selectFrom(MEMBERSHIPS).fetch() } }.map { it.accountId }.toSet()).containsExactly(account)
        assertThat(tx.run { dsl.fetchCount(MEMBERSHIPS) }).isZero()
    }

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

    /**
     * Architecture review, 5 October 2026: after a restore into a database without the app's
     * role, the app started with a warning, and for the Compose setup's superuser row-level
     * security did nothing. The app runs this check at startup; the tests' database user is a
     * superuser too.
     */
    @Test
    fun `the app refuses to start when row-level security can't hold`() {
        fun check(role: String) = RowLevelSecurityCheck(dataSource, TenantAwareTransactionManager(dataSource, role), role)
        val missing = "honestrobin_missing_${UUID.randomUUID().toString().take(8)}"

        // Restored without the role.
        assertThatThrownBy { check(missing).verify() }.isInstanceOf(FailureAnalyzedException::class.java)
            .hasMessageContaining("Refusing to start").hasMessageContaining("doesn't exist")
        // Nor does any transaction begin without it, so none runs on as the superuser.
        val withoutRole = TransactionTemplate(TenantAwareTransactionManager(dataSource, missing))
        assertThatThrownBy { withoutRole.execute { dsl.fetchValue("select current_user") } }
            .isInstanceOf(CannotCreateTransactionException::class.java)

        // No role to switch to, so transactions would run as the superuser itself.
        assertThatThrownBy { check("").verify() }.isInstanceOf(FailureAnalyzedException::class.java)
            .hasMessageContaining("bypasses row-level security")

        // The role created after the restore, which skipped its rights on the tables.
        val late = "honestrobin_late_${UUID.randomUUID().toString().take(8)}"
        asDatabaseUser("create role $late nologin")
        try {
            assertThatThrownBy { check(late).verify() }.isInstanceOf(FailureAnalyzedException::class.java)
                .hasMessageContaining("has no rights on the app's tables")
        } finally {
            asDatabaseUser("drop role $late")
        }

        // A database user that may not switch to the role.
        val outsider = "honestrobin_outsider_${UUID.randomUUID().toString().take(8)}"
        asDatabaseUser("create role $outsider login password 'outsider-test-password'")
        try {
            val asOutsider = DriverManagerDataSource(TestDatabase.sharedUrl, outsider, "outsider-test-password")
            assertThatThrownBy {
                RowLevelSecurityCheck(asOutsider, TenantAwareTransactionManager(asOutsider, "honestrobin_app"), "honestrobin_app").verify()
            }.isInstanceOf(FailureAnalyzedException::class.java).hasMessageContaining("may not switch to the role")
        } finally {
            asDatabaseUser("drop role $outsider")
        }

        // As it ships, it starts.
        check("honestrobin_app").verify()
    }

    /** The check above runs at startup, before the web server takes requests: a second app without its role doesn't start. */
    @Test
    fun `the app doesn't start without its database role`() {
        val started = runCatching {
            SpringApplicationBuilder(HonestRobinApplication::class.java)
                .profiles("test")
                .run(
                    "--honestrobin.db.app-role=honestrobin_missing_${UUID.randomUUID().toString().take(8)}",
                    "--server.port=0",
                    "--db-scheduler.enabled=false",
                    "--spring.datasource.url=${TestDatabase.sharedUrl}",
                    "--spring.datasource.username=${TestDatabase.username}",
                    "--spring.datasource.password=${TestDatabase.password}",
                ).close()
        }
        val failure = started.exceptionOrNull()
        assertThat(failure).describedAs("the app started without its database role").isNotNull()
        assertThat(generateSequence(failure) { it.cause }.toList()).anySatisfy { cause ->
            assertThat(cause).isInstanceOf(RowLevelSecurityNotEffective::class.java).hasMessageContaining("doesn't exist")
        }
    }

    private data class Session(val user: String, val bypass: String, val account: String, val auditOff: String)

    /**
     * Second review of #25: the migrations switch row-level security's bypass on for their whole
     * database session. On a connection from the app's pool, that would outlast them, and a query
     * outside a transaction on that connection would bypass the policies. A second app starts on an
     * empty database, so every migration runs, and every connection its pool can give is read.
     */
    @Test
    fun `no pooled connection is left bypassing row-level security after startup`() {
        val context = SpringApplicationBuilder(HonestRobinApplication::class.java)
            .profiles("test")
            .run(
                "--server.port=0",
                "--db-scheduler.enabled=false",
                "--spring.datasource.url=${TestDatabase.freshDatabase("pool")}",
                "--spring.datasource.username=${TestDatabase.username}",
                "--spring.datasource.password=${TestDatabase.password}",
            )
        try {
            val pool = context.getBean(DataSource::class.java).unwrap(HikariDataSource::class.java)
            val borrowed = mutableListOf<Connection>()
            val seen = try {
                // Each one is held, so the pool has to give every connection it has, the ones the
                // migrations used included. They're read outside any transaction, as autocommit.
                repeat(pool.maximumPoolSize) { borrowed += pool.connection }
                borrowed.map { con ->
                    assertThat(con.autoCommit).isTrue()
                    con.createStatement().use { st ->
                        st.executeQuery(
                            "select current_user, coalesce(current_setting('honestrobin.rls_bypass', true), ''), " +
                                "coalesce(current_setting('honestrobin.account_id', true), ''), " +
                                "coalesce(current_setting('honestrobin.audit_disabled', true), '')",
                        ).use { rs ->
                            rs.next()
                            Session(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))
                        }
                    }
                }
            } finally {
                borrowed.forEach { it.close() }
            }
            assertThat(seen).hasSize(pool.maximumPoolSize)
            assertThat(seen.filter { it.bypass == "on" }).describedAs("pooled connections still bypassing row-level security").isEmpty()
            assertThat(seen.filter { it.account.isNotEmpty() }).describedAs("pooled connections still set to an account").isEmpty()
            // Transactions don't set this one again, so a leftover would switch the audit log off in each of them.
            assertThat(seen.filter { it.auditOff == "on" }).describedAs("pooled connections with the audit log switched off").isEmpty()
            assertThat(seen.map { it.user }.toSet()).describedAs("pooled connections left switched to another role").containsExactly(TestDatabase.username)
        } finally {
            context.close()
        }
    }
}
