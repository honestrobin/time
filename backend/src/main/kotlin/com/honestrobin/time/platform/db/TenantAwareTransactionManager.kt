// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.db

import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import javax.sql.DataSource

/**
 * Sets the tenant, actor and RLS-bypass GUCs as transaction-local settings on every
 * transaction. Queries run outside a transaction see no tenant rows at all.
 */
class TenantAwareTransactionManager(
    dataSource: DataSource,
    private val appRole: String,
) : DataSourceTransactionManager(dataSource) {
    override fun prepareTransactionalConnection(con: Connection, definition: TransactionDefinition) {
        super.prepareTransactionalConnection(con, definition)
        // Drop to the unprivileged role for the transaction so RLS applies even to superusers.
        // Without the role, PostgreSQL refuses the switch and the transaction never begins: it
        // doesn't go on as the login user, which may bypass row-level security. RowLevelSecurityCheck
        // says why at startup.
        if (appRole.isNotBlank()) {
            con.prepareStatement("select set_config('role', ?, true)").use { ps ->
                ps.setString(1, appRole)
                ps.executeQuery().close()
            }
        }
        apply(con, DbContext.current())
    }

    companion object {
        private const val SQL = "select set_config('honestrobin.account_id', ?, true), set_config('honestrobin.actor_id', ?, true), " +
            "set_config('honestrobin.actor_type', ?, true), set_config('honestrobin.ip', ?, true), set_config('honestrobin.rls_bypass', ?, true)"

        fun apply(con: Connection, ctx: DbContext) {
            con.prepareStatement(SQL).use { ps ->
                ps.setString(1, ctx.accountId?.toString() ?: "")
                ps.setString(2, ctx.actorUserId?.toString() ?: "")
                ps.setString(3, ctx.actorType.sql)
                ps.setString(4, ctx.ip ?: "")
                ps.setString(5, if (ctx.bypassRls) "on" else "off")
                ps.executeQuery().close()
            }
        }

        internal fun applyToCurrentTransaction(ctx: DbContext) {
            currentConnection()?.let { apply(it, ctx) }
        }

        /** Sets a transaction-local GUC (e.g. `honestrobin.audit_reason`) on the current transaction. */
        fun setLocal(name: String, value: String) {
            val con = checkNotNull(currentConnection()) { "setLocal requires an open transaction" }
            con.prepareStatement("select set_config(?, ?, true)").use { ps ->
                ps.setString(1, name)
                ps.setString(2, value)
                ps.executeQuery().close()
            }
        }

        /** The JDBC connection of the transaction bound to this thread (there is one DataSource). */
        private fun currentConnection(): Connection? =
            TransactionSynchronizationManager.getResourceMap().values.filterIsInstance<ConnectionHolder>().singleOrNull()?.connection
    }
}
