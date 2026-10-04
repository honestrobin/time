// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.db

import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

enum class ActorType(val sql: String) {
    USER("user"),
    API_TOKEN("api_token"),
    SYSTEM("system"),
}

/**
 * Who is acting and on which tenant. Pushed into Postgres at transaction start
 * (see [TenantAwareTransactionManager]) where RLS policies and the audit trigger read it.
 */
data class DbContext(
    val accountId: UUID? = null,
    val actorUserId: UUID? = null,
    val actorType: ActorType = ActorType.SYSTEM,
    val ip: String? = null,
    val bypassRls: Boolean = false,
) {
    companion object {
        private val holder = ThreadLocal<DbContext?>()

        fun current(): DbContext = holder.get() ?: DbContext()

        fun set(ctx: DbContext?) {
            if (ctx == null) holder.remove() else holder.set(ctx)
        }

        /** Runs [block] with [ctx]; if a transaction is already open, the new context is applied to it too. */
        fun <T> with(ctx: DbContext, block: () -> T): T {
            val previous = holder.get()
            holder.set(ctx)
            val inTx = TransactionSynchronizationManager.isActualTransactionActive()
            if (inTx) TenantAwareTransactionManager.applyToCurrentTransaction(ctx)
            try {
                return block()
            } finally {
                set(previous)
                if (inTx) TenantAwareTransactionManager.applyToCurrentTransaction(previous ?: DbContext())
            }
        }

        /** Cross-tenant access for system code paths (login, schedulers). Use sparingly. */
        fun <T> system(block: () -> T): T = with(current().copy(bypassRls = true), block)

        fun <T> forAccount(accountId: UUID, block: () -> T): T =
            with(current().copy(accountId = accountId, bypassRls = false), block)

        /**
         * For what an anonymous visitor causes in an account (a client opening a public invoice):
         * no actor and no IP, so a visitor who happens to be signed in to their own account isn't
         * written into this account's audit log by name.
         */
        fun <T> anonymouslyForAccount(accountId: UUID, block: () -> T): T = with(DbContext(accountId = accountId), block)
    }
}
