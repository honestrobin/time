// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.importers.harvest

import com.honestrobin.time.db.Tables.EXTERNAL_LINKS
import org.jooq.DSLContext
import org.jooq.JSONB
import java.util.UUID

/**
 * external_links for one account and system: the idempotency backbone of imports
 * (re-running updates in place, never duplicates). Lookups are cached per import run.
 */
class Links(private val dsl: DSLContext, private val accountId: UUID, private val system: String = "harvest") {
    private val cache = HashMap<String, HashMap<String, UUID>>()

    fun preload(entityType: String) {
        val map = cache.getOrPut(entityType) { HashMap() }
        dsl.select(EXTERNAL_LINKS.EXTERNAL_ID, EXTERNAL_LINKS.ENTITY_ID).from(EXTERNAL_LINKS)
            .where(EXTERNAL_LINKS.ACCOUNT_ID.eq(accountId)).and(EXTERNAL_LINKS.SYSTEM.eq(system)).and(EXTERNAL_LINKS.ENTITY_TYPE.eq(entityType))
            .fetch().forEach { map[it.value1()] = it.value2() }
    }

    fun find(entityType: String, externalIds: Collection<String>): Map<String, UUID> {
        val map = cache.getOrPut(entityType) { HashMap() }
        val missing = externalIds.filter { it !in map }
        if (missing.isNotEmpty()) {
            dsl.select(EXTERNAL_LINKS.EXTERNAL_ID, EXTERNAL_LINKS.ENTITY_ID).from(EXTERNAL_LINKS)
                .where(EXTERNAL_LINKS.ACCOUNT_ID.eq(accountId)).and(EXTERNAL_LINKS.SYSTEM.eq(system))
                .and(EXTERNAL_LINKS.ENTITY_TYPE.eq(entityType)).and(EXTERNAL_LINKS.EXTERNAL_ID.`in`(missing))
                .fetch().forEach { map[it.value1()] = it.value2() }
        }
        return externalIds.mapNotNull { id -> map[id]?.let { id to it } }.toMap()
    }

    fun get(entityType: String, externalId: Long?): UUID? = externalId?.let { find(entityType, listOf(it.toString()))[it.toString()] }

    fun put(entityType: String, externalId: String, entityId: UUID, meta: String? = null) {
        dsl.insertInto(EXTERNAL_LINKS)
            .set(EXTERNAL_LINKS.ACCOUNT_ID, accountId).set(EXTERNAL_LINKS.SYSTEM, system).set(EXTERNAL_LINKS.ENTITY_TYPE, entityType)
            .set(EXTERNAL_LINKS.EXTERNAL_ID, externalId).set(EXTERNAL_LINKS.ENTITY_ID, entityId)
            .set(EXTERNAL_LINKS.META, meta?.let(JSONB::valueOf))
            .onConflict(EXTERNAL_LINKS.ACCOUNT_ID, EXTERNAL_LINKS.SYSTEM, EXTERNAL_LINKS.ENTITY_TYPE, EXTERNAL_LINKS.EXTERNAL_ID)
            .doUpdate().set(EXTERNAL_LINKS.ENTITY_ID, entityId).set(EXTERNAL_LINKS.META, meta?.let(JSONB::valueOf))
            .execute()
        cache.getOrPut(entityType) { HashMap() }[externalId] = entityId
    }

    /** Batch variant for large pages; rows are (externalId, entityId, meta). */
    fun putAll(entityType: String, rows: List<Triple<String, UUID, String?>>) {
        if (rows.isEmpty()) return
        val queries = rows.map { (ext, id, meta) ->
            dsl.insertInto(EXTERNAL_LINKS)
                .set(EXTERNAL_LINKS.ACCOUNT_ID, accountId).set(EXTERNAL_LINKS.SYSTEM, system).set(EXTERNAL_LINKS.ENTITY_TYPE, entityType)
                .set(EXTERNAL_LINKS.EXTERNAL_ID, ext).set(EXTERNAL_LINKS.ENTITY_ID, id).set(EXTERNAL_LINKS.META, meta?.let(JSONB::valueOf))
                .onConflict(EXTERNAL_LINKS.ACCOUNT_ID, EXTERNAL_LINKS.SYSTEM, EXTERNAL_LINKS.ENTITY_TYPE, EXTERNAL_LINKS.EXTERNAL_ID)
                .doUpdate().set(EXTERNAL_LINKS.ENTITY_ID, id).set(EXTERNAL_LINKS.META, meta?.let(JSONB::valueOf))
        }
        dsl.batch(queries).execute()
        val map = cache.getOrPut(entityType) { HashMap() }
        rows.forEach { (ext, id, _) -> map[ext] = id }
    }

    fun evict(entityType: String) {
        cache.remove(entityType)
    }
}
