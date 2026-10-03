// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.security

import com.honestrobin.time.db.Tables.RATE_LIMIT_EVENTS
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant

/**
 * Sliding-window rate limits kept in the database, so every server of an instance counts the
 * same events. Counting and recording run in the caller's transaction: if it rolls back (no
 * mail went out, nobody signed up), nothing is counted. [recordAlways] is for failures that
 * must count although the request rolls back, like a wrong password.
 */
@Component
class RateLimiter(private val dsl: DSLContext, transactions: PlatformTransactionManager) {
    private val inTransaction = TransactionTemplate(transactions)
    private val independently = TransactionTemplate(transactions).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    /** Events in [bucket] within [window] before [now]. Events after [now] (a clock moved back) don't count. */
    fun count(bucket: String, window: Duration, now: Instant = Instant.now()): Int = inTransaction.execute {
        dsl.fetchCount(RATE_LIMIT_EVENTS, RATE_LIMIT_EVENTS.BUCKET.eq(bucket).and(RATE_LIMIT_EVENTS.AT.gt(now.minus(window))).and(RATE_LIMIT_EVENTS.AT.le(now)))
    }!!

    /**
     * Records [n] events if the bucket stays within [limit] for the [window]; otherwise records
     * nothing and returns false. Concurrent calls for one bucket wait for each other.
     */
    fun tryAcquire(bucket: String, limit: Int, window: Duration, now: Instant = Instant.now(), n: Int = 1): Boolean = inTransaction.execute {
        dsl.execute("select pg_advisory_xact_lock(hashtextextended(?, 7234002))", bucket)
        if (count(bucket, window, now) + n > limit) {
            false
        } else {
            insert(bucket, now, n)
            true
        }
    }!!

    /** Records [n] events in a transaction of their own, so they count even if the request fails. */
    fun recordAlways(bucket: String, now: Instant = Instant.now(), n: Int = 1) {
        independently.executeWithoutResult { insert(bucket, now, n) }
    }

    fun clear(bucket: String) {
        inTransaction.executeWithoutResult { dsl.deleteFrom(RATE_LIMIT_EVENTS).where(RATE_LIMIT_EVENTS.BUCKET.eq(bucket)).execute() }
    }

    /** Drops events older than any window in use (the longest is a day). */
    fun purge(before: Instant = Instant.now().minus(Duration.ofDays(2))): Int =
        inTransaction.execute { dsl.deleteFrom(RATE_LIMIT_EVENTS).where(RATE_LIMIT_EVENTS.AT.lt(before)).execute() }!!

    private fun insert(bucket: String, now: Instant, n: Int) {
        val rows = List(n) { dsl.insertInto(RATE_LIMIT_EVENTS).set(RATE_LIMIT_EVENTS.BUCKET, bucket).set(RATE_LIMIT_EVENTS.AT, now) }
        dsl.batch(rows).execute()
    }
}
