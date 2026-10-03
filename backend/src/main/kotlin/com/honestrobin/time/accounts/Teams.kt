// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.accounts

import com.honestrobin.time.db.Tables.TEAMS
import com.honestrobin.time.db.Tables.MEMBERSHIPS
import com.honestrobin.time.db.Tables.TEAM_MEMBERSHIPS
import com.honestrobin.time.platform.security.Current
import com.honestrobin.time.platform.security.Member
import com.honestrobin.time.platform.web.NotFoundException
import com.honestrobin.time.platform.web.ValidationException
import com.honestrobin.time.platform.web.Versioned
import io.swagger.v3.oas.annotations.tags.Tag
import org.jooq.DSLContext
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** Teams group people for filtering reports (Harvest calls these "roles"). They grant no permissions. */
data class TeamView(val id: UUID, val name: String, val membershipIds: List<UUID>, val createdAt: Instant, val updatedAt: Instant) : Versioned {
    override val version get() = updatedAt
}

data class TeamInput(val name: String? = null, val membershipIds: List<UUID>? = null)

@Service
class TeamService(private val dsl: DSLContext, private val access: Access) {
    @Transactional(readOnly = true)
    fun list(m: Member): List<TeamView> {
        m.requireManagerOrAdmin()
        val members = dsl.selectFrom(TEAM_MEMBERSHIPS).fetch().groupBy({ it.teamId }) { it.membershipId }
        return dsl.selectFrom(TEAMS).orderBy(TEAMS.NAME).fetch { TeamView(it.id, it.name, members[it.id].orEmpty(), it.createdAt, it.updatedAt) }
    }

    @Transactional
    fun create(m: Member, input: TeamInput): TeamView {
        m.requireWritable()
        m.requireAdmin()
        if (input.name.isNullOrBlank()) throw ValidationException("name", "Enter a team name")
        val r = dsl.newRecord(TEAMS).apply {
            accountId = m.accountId
            name = input.name.trim()
        }
        store(r)
        input.membershipIds?.let { setMembers(m, r.id, it) }
        return list(m).first { it.id == r.id }
    }

    @Transactional
    fun update(m: Member, id: UUID, input: TeamInput): TeamView {
        m.requireWritable()
        m.requireAdmin()
        val r = dsl.selectFrom(TEAMS).where(TEAMS.ID.eq(id)).fetchOne() ?: throw NotFoundException("Team")
        input.name?.let { if (it.isBlank()) throw ValidationException("name", "Enter a team name") else r.name = it.trim() }
        store(r)
        input.membershipIds?.let { setMembers(m, id, it) }
        return list(m).first { it.id == id }
    }

    @Transactional
    fun delete(m: Member, id: UUID) {
        m.requireWritable()
        m.requireAdmin()
        if (dsl.deleteFrom(TEAMS).where(TEAMS.ID.eq(id)).execute() == 0) throw NotFoundException("Team")
    }

    private fun store(r: com.honestrobin.time.db.tables.records.TeamsRecord) {
        try {
            r.store()
        } catch (e: DuplicateKeyException) {
            throw ValidationException("name", "A team with this name already exists")
        }
    }

    private fun setMembers(m: Member, teamId: UUID, membershipIds: List<UUID>) {
        access.requireInAccount(MEMBERSHIPS, membershipIds, "membership_ids", "person")
        dsl.deleteFrom(TEAM_MEMBERSHIPS).where(TEAM_MEMBERSHIPS.TEAM_ID.eq(teamId)).and(TEAM_MEMBERSHIPS.MEMBERSHIP_ID.notIn(membershipIds)).execute()
        membershipIds.distinct().forEach {
            dsl.insertInto(TEAM_MEMBERSHIPS).set(TEAM_MEMBERSHIPS.ACCOUNT_ID, m.accountId).set(TEAM_MEMBERSHIPS.TEAM_ID, teamId)
                .set(TEAM_MEMBERSHIPS.MEMBERSHIP_ID, it).onConflictDoNothing().execute()
        }
    }
}

@RestController
@RequestMapping("/api/v1/teams")
@Tag(name = "teams")
class TeamController(private val teams: TeamService) {
    @GetMapping
    fun list() = teams.list(Current.member())

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody body: TeamInput) = teams.create(Current.member(), body)

    @PatchMapping("/{id}")
    fun update(@PathVariable id: UUID, @RequestBody body: TeamInput) = teams.update(Current.member(), id, body)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) = teams.delete(Current.member(), id)
}
