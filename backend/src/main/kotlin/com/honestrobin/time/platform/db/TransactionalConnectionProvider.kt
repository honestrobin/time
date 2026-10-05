// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.db

import org.jooq.ConnectionProvider
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import javax.sql.DataSource

/**
 * Gives jOOQ the connection of the transaction it runs in, and refuses a query outside one.
 * Only a transaction switches to the app's role and says which account it's for
 * ([TenantAwareTransactionManager]). Outside one, a query would run as the database user itself,
 * which in the Compose setup is a superuser that row-level security doesn't apply to. So every
 * query runs in a transaction, or not at all (decision record 0027).
 */
class TransactionalConnectionProvider(private val dataSource: DataSource) : ConnectionProvider {
    override fun acquire(): Connection {
        val holder = TransactionSynchronizationManager.getResource(dataSource) as? ConnectionHolder
        if (holder == null || !TenantAwareTransactionManager.isOpen(holder.connection)) throw QueryOutsideTransaction()
        return DataSourceUtils.getConnection(dataSource)
    }

    override fun release(connection: Connection) = DataSourceUtils.releaseConnection(connection, dataSource)
}

class QueryOutsideTransaction : IllegalStateException(
    "Refused a database query outside a transaction: it would run as the database user, and row-level security couldn't keep accounts apart. " +
        "Run it in a transaction (@Transactional, Tx.run or Tx.system); after a commit, in a new one (PROPAGATION_REQUIRES_NEW).",
)
