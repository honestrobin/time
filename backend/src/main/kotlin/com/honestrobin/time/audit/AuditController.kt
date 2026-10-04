// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.audit

import com.honestrobin.time.db.Tables.AUDIT_LOG
import com.honestrobin.time.db.Tables.USERS
import com.honestrobin.time.platform.db.TenantAwareTransactionManager
import com.honestrobin.time.platform.security.Current
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.tags.Tag
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class AuditEntry(
    val id: UUID,
    val at: Instant,
    val action: String,
    val entityType: String,
    val entityId: UUID?,
    val actorUserId: UUID?,
    val actorName: String?,
    val actorType: String,
    val diff: JsonNode,
    val reason: String?,
    val ip: String?,
)

data class AuditPage(val entries: List<AuditEntry>, val nextCursor: String?)

@Service
class AuditService(private val dsl: DSLContext, private val mapper: ObjectMapper) {

    /** Explicit, non-table events (sign-ins, exports, unlocks with a reason). Row changes are audited by trigger. */
    @Transactional
    fun record(action: String, entityType: String, entityId: UUID?, details: Map<String, Any?> = emptyMap(), reason: String? = null, accountId: UUID? = null) {
        dsl.execute(
            """
            insert into audit_log (account_id, actor_user_id, actor_type, action, entity_type, entity_id, diff, reason, ip)
            values (coalesce(?, nullif(current_setting('honestrobin.account_id', true), '')::uuid),
                    nullif(current_setting('honestrobin.actor_id', true), '')::uuid,
                    coalesce(nullif(current_setting('honestrobin.actor_type', true), ''), 'system'),
                    ?, ?, ?, ?::jsonb, ?, nullif(current_setting('honestrobin.ip', true), ''))
            """.trimIndent(),
            accountId, action, entityType, entityId, mapper.writeValueAsString(details), reason,
        )
    }

    /** Runs [block] so that every row change it makes is audited with [reason]. Must be called inside a transaction. */
    fun <T> withReason(reason: String, block: () -> T): T {
        TenantAwareTransactionManager.setLocal("honestrobin.audit_reason", reason)
        try {
            return block()
        } finally {
            TenantAwareTransactionManager.setLocal("honestrobin.audit_reason", "")
        }
    }

    @Transactional(readOnly = true)
    fun list(entityType: String?, entityId: UUID?, before: UUID?, limit: Int): AuditPage {
        val member = Current.member()
        member.requireAdmin()
        val rows = dsl.select(AUDIT_LOG.asterisk(), USERS.NAME)
            .from(AUDIT_LOG).leftJoin(USERS).on(USERS.ID.eq(AUDIT_LOG.ACTOR_USER_ID))
            .where(AUDIT_LOG.ACCOUNT_ID.eq(member.accountId))
            .and(if (entityType != null) AUDIT_LOG.ENTITY_TYPE.eq(entityType) else DSL.noCondition())
            .and(if (entityId != null) AUDIT_LOG.ENTITY_ID.eq(entityId) else DSL.noCondition())
            .and(if (before != null) AUDIT_LOG.ID.lt(before) else DSL.noCondition())
            .orderBy(AUDIT_LOG.ID.desc())
            .limit(limit.coerceIn(1, 200) + 1)
            .fetch {
                AuditEntry(
                    it[AUDIT_LOG.ID], it[AUDIT_LOG.AT], it[AUDIT_LOG.ACTION], it[AUDIT_LOG.ENTITY_TYPE], it[AUDIT_LOG.ENTITY_ID],
                    it[AUDIT_LOG.ACTOR_USER_ID], it[USERS.NAME], it[AUDIT_LOG.ACTOR_TYPE], mapper.readTree(it[AUDIT_LOG.DIFF].data()),
                    it[AUDIT_LOG.REASON], it[AUDIT_LOG.IP],
                )
            }
        val page = rows.take(limit.coerceIn(1, 200))
        return AuditPage(page, if (rows.size > page.size) page.last().id.toString() else null)
    }
}

@RestController
@RequestMapping("/api/v1/audit_log")
@Tag(name = "audit", description = "Append-only audit log (admins)")
class AuditController(private val audit: AuditService) {
    @GetMapping
    fun list(
        @RequestParam(name = "entity_type", required = false) entityType: String?,
        @RequestParam(name = "entity_id", required = false) entityId: UUID?,
        @RequestParam(required = false) cursor: UUID?,
        @RequestParam(defaultValue = "50") limit: Int,
    ) = audit.list(entityType, entityId, cursor, limit)
}
