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
    @Volatile
    private var roleUsable: Boolean? = null

    override fun prepareTransactionalConnection(con: Connection, definition: TransactionDefinition) {
        super.prepareTransactionalConnection(con, definition)
        // Drop to the unprivileged role for the transaction so RLS applies even to superusers.
        if (appRole.isNotBlank() && isRoleUsable(con)) {
            con.prepareStatement("select set_config('role', ?, true)").use { ps ->
                ps.setString(1, appRole)
                ps.executeQuery().close()
            }
        }
        apply(con, DbContext.current())
    }

    private fun isRoleUsable(con: Connection): Boolean = roleUsable ?: run {
        val sql = "select case when exists (select 1 from pg_roles where rolname = ?) then pg_has_role(current_user, ?, 'MEMBER') else false end"
        val usable = con.prepareStatement(sql).use { ps ->
            ps.setString(1, appRole)
            ps.setString(2, appRole)
            ps.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
        }
        if (!usable) logger.warn("Database role '$appRole' is not available; row-level security relies on FORCE RLS only")
        roleUsable = usable
        usable
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
