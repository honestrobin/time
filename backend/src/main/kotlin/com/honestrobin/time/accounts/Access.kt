// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.accounts

import com.honestrobin.time.db.Tables.PROJECT_MEMBERS
import com.honestrobin.time.platform.security.Member
import com.honestrobin.time.platform.security.Role
import com.honestrobin.time.platform.web.ForbiddenException
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Permission checks from spec §11. Admins can do everything; managers act on the projects
 * they manage (`project_members.is_manager`); members act on their own time and expenses.
 */
@Component
class Access(private val dsl: DSLContext) {

    /** Projects [m] manages, or null meaning "all" for admins. */
    fun managedProjectIds(m: Member): Set<UUID>? = when (m.role) {
        Role.ADMIN -> null
        Role.MANAGER -> dsl.select(PROJECT_MEMBERS.PROJECT_ID).from(PROJECT_MEMBERS)
            .where(PROJECT_MEMBERS.MEMBERSHIP_ID.eq(m.membershipId)).and(PROJECT_MEMBERS.IS_MANAGER.isTrue)
            .fetchSet(PROJECT_MEMBERS.PROJECT_ID)
        Role.MEMBER -> emptySet()
    }

    fun manages(m: Member, projectId: UUID): Boolean = when (m.role) {
        Role.ADMIN -> true
        Role.MANAGER -> dsl.fetchExists(
            PROJECT_MEMBERS,
            PROJECT_MEMBERS.PROJECT_ID.eq(projectId).and(PROJECT_MEMBERS.MEMBERSHIP_ID.eq(m.membershipId)).and(PROJECT_MEMBERS.IS_MANAGER.isTrue),
        )
        Role.MEMBER -> false
    }

    fun requireManages(m: Member, projectId: UUID) {
        if (!manages(m, projectId)) throw ForbiddenException("You don't manage this project")
    }

    /** Creating projects, clients and tasks: admins, and managers allowed to manage projects. */
    fun requireCanCreateProjects(m: Member) {
        if (!m.canManageProjects) throw ForbiddenException("You don't have permission to manage projects")
    }

    /**
     * Fails unless every id is a row of [table] in the current account. Foreign keys don't see
     * row-level security, so an id from another account would otherwise be accepted.
     */
    fun requireInAccount(table: org.jooq.Table<*>, ids: Collection<UUID>, field: String, what: String) {
        val distinct = ids.toSet()
        if (distinct.isEmpty()) return
        val id = table.field("id", UUID::class.java)!!
        if (dsl.fetchCount(table, id.`in`(distinct)) != distinct.size) throw com.honestrobin.time.platform.web.ValidationException(field, "Unknown $what")
    }

    fun isAssigned(membershipId: UUID, projectId: UUID): Boolean = dsl.fetchExists(
        PROJECT_MEMBERS,
        PROJECT_MEMBERS.PROJECT_ID.eq(projectId).and(PROJECT_MEMBERS.MEMBERSHIP_ID.eq(membershipId)).and(PROJECT_MEMBERS.IS_ACTIVE.isTrue),
    )

    /** Whose time [m] may see and edit on [projectId]: their own, or anyone's on a project they manage. */
    fun canActOnTimeOf(m: Member, ownerMembershipId: UUID, projectId: UUID): Boolean =
        ownerMembershipId == m.membershipId || manages(m, projectId)

    /** People [m] may see: null = everyone (admins); managers see people on their projects. */
    fun visibleMembershipIds(m: Member): Set<UUID>? = when (m.role) {
        Role.ADMIN -> null
        Role.MANAGER -> {
            val projects = managedProjectIds(m)!!
            dsl.selectDistinct(PROJECT_MEMBERS.MEMBERSHIP_ID).from(PROJECT_MEMBERS)
                .where(PROJECT_MEMBERS.PROJECT_ID.`in`(projects)).fetchSet(PROJECT_MEMBERS.MEMBERSHIP_ID) + m.membershipId
        }
        Role.MEMBER -> setOf(m.membershipId)
    }
}
