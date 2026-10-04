// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.accounts

import com.honestrobin.time.platform.web.idCursor
import com.honestrobin.time.platform.mail.OutboundMail
import com.honestrobin.time.auth.AuthService
import com.honestrobin.time.auth.TokenPurpose
import com.honestrobin.time.db.Tables.ACCOUNTS
import com.honestrobin.time.db.Tables.LOGIN_TOKENS
import com.honestrobin.time.db.Tables.MEMBERSHIPS
import com.honestrobin.time.db.Tables.TEAMS
import com.honestrobin.time.db.Tables.TEAM_MEMBERSHIPS
import com.honestrobin.time.db.Tables.TIME_ENTRIES
import com.honestrobin.time.db.Tables.USERS
import com.honestrobin.time.db.tables.records.MembershipsRecord
import com.honestrobin.time.platform.HonestRobinProperties
import com.honestrobin.time.platform.mail.Mailer
import com.honestrobin.time.platform.security.Current
import com.honestrobin.time.platform.security.Member
import com.honestrobin.time.platform.security.Role
import com.honestrobin.time.platform.web.ConflictException
import com.honestrobin.time.platform.web.ETags
import com.honestrobin.time.platform.web.NotFoundException
import com.honestrobin.time.platform.web.Page
import com.honestrobin.time.platform.web.Patch
import com.honestrobin.time.platform.web.Patches
import com.honestrobin.time.platform.web.Names
import com.honestrobin.time.platform.web.ValidationException
import com.honestrobin.time.platform.web.Versioned
import com.honestrobin.time.platform.web.Views
import com.honestrobin.time.platform.web.clampLimit
import com.fasterxml.jackson.annotation.JsonView
import tools.jackson.databind.JsonNode
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID

data class PersonView(
    /** The membership id: a person as seen by this account. */
    val id: UUID,
    val userId: UUID?,
    val name: String,
    val email: String,
    val role: String,
    /** pending_invite (imported, not yet invited), invited, or active (can sign in). */
    val status: String,
    val isActive: Boolean,
    val isContractor: Boolean,
    val isBillableDefault: Boolean,
    val weeklyCapacitySeconds: Int,
    val hasAccessToAllFutureProjects: Boolean,
    @JsonView(Views.Rates::class) val defaultBillableRate: Long?,
    @JsonView(Views.Rates::class) val costRate: Long?,
    val canSeeRates: Boolean,
    val canManageProjects: Boolean,
    val canManageInvoices: Boolean,
    val teamIds: List<UUID>,
    val invitedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Whether they use two-factor sign-in; shown to admins only (null otherwise, and for people who can't sign in yet). */
    val twoFactorEnabled: Boolean? = null,
) : Versioned {
    override val version get() = updatedAt
}

data class PersonInput(
    val name: String? = null,
    val email: String? = null,
    val role: String? = null,
    val isActive: Boolean? = null,
    val isContractor: Boolean? = null,
    val isBillableDefault: Boolean? = null,
    val weeklyCapacitySeconds: Int? = null,
    val hasAccessToAllFutureProjects: Boolean? = null,
    val defaultBillableRate: Long? = null,
    val costRate: Long? = null,
    val canSeeRates: Boolean? = null,
    val canManageProjects: Boolean? = null,
    val canManageInvoices: Boolean? = null,
    val teamIds: List<UUID>? = null,
    /** On create: send the invitation email right away (default true). */
    val sendInvite: Boolean? = null,
)

@Service
class PeopleService(
    private val dsl: DSLContext,
    private val access: Access,
    private val auth: AuthService,
    private val mailer: Mailer,
    private val props: HonestRobinProperties,
    private val outbound: OutboundMail,
    private val seats: SeatGate,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun list(m: Member, isActive: Boolean?, cursor: String?, limit: Int?): Page<PersonView> {
        m.requireManagerOrAdmin()
        val visible = access.visibleMembershipIds(m)
        val n = clampLimit(limit, 500)
        val rows = dsl.selectFrom(MEMBERSHIPS)
            .where(if (visible != null) MEMBERSHIPS.ID.`in`(visible) else DSL.noCondition())
            .and(if (isActive != null) MEMBERSHIPS.IS_ACTIVE.eq(isActive) else DSL.noCondition())
            .and(if (cursor != null) MEMBERSHIPS.ID.gt(idCursor(cursor)) else DSL.noCondition())
            .orderBy(MEMBERSHIPS.ID).limit(n + 1).fetch()
        return Page.of(views(rows), n) { it.id.toString() }
    }

    @Transactional(readOnly = true)
    fun get(m: Member, id: UUID): PersonView {
        val visible = access.visibleMembershipIds(m)
        if (visible != null && id !in visible) throw NotFoundException("Person")
        return views(listOf(load(id))).single()
    }

    @Transactional
    fun create(m: Member, input: PersonInput): PersonView {
        m.requireAdmin()
        m.requireWritable()
        val errors = mutableMapOf<String, String>()
        if (input.name.isNullOrBlank()) errors["name"] = "Enter a name"
        if (input.email.isNullOrBlank() || !input.email.contains('@')) errors["email"] = "Enter a valid email address"
        if (errors.isNotEmpty()) throw ValidationException(errors)
        val r = dsl.newRecord(MEMBERSHIPS).apply {
            accountId = m.accountId
            status = "pending_invite"
        }
        apply(m, r, Patch.all(input, presentFields(input)), creating = true)
        try {
            r.store()
        } catch (e: DuplicateKeyException) {
            throw ValidationException("email", "Someone with this email is already in the account")
        }
        input.teamIds?.let { setTeams(m, r.id, it) }
        if (input.sendInvite != false) invite(m, r.id)
        return views(listOf(load(r.id))).single()
    }

    @Transactional
    fun update(m: Member, id: UUID, patch: Patch<PersonInput>): PersonView {
        m.requireAdmin()
        m.requireWritable()
        val r = load(id)
        ETags.checkIfMatch(r.updatedAt)
        apply(m, r, patch, creating = false)
        try {
            r.store()
        } catch (e: DuplicateKeyException) {
            throw ValidationException("email", "Someone with this email is already in the account")
        }
        patch.value.teamIds?.let { setTeams(m, id, it) }
        if (patch.value.isActive == false) stopRunningTimer(id)
        return views(listOf(load(id))).single()
    }

    /** Sends (or re-sends) the invitation email. */
    @Transactional
    fun invite(m: Member, id: UUID): PersonView {
        m.requireWritable()
        m.requireAdmin()
        val r = load(id)
        if (r.status == "active") throw ConflictException("already_active", "This person already has access")
        if (!r.isActive) throw ConflictException("inactive", "Reactivate this person before inviting them")
        outbound.checkInvite(m.accountId, m.userId, r)
        if (r.status != "invited") seats.requireSeat(m.accountId)
        val account = dsl.selectFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(m.accountId)).fetchOne()!!
        val token = auth.issueToken(null, r.email, TokenPurpose.INVITE, membershipId = r.id)
        r.status = "invited"
        r.invitedAt = Instant.now(clock)
        r.store()
        mailer.send(
            "invite", r.email, Locale.forLanguageTag(account.locale),
            mapOf("name" to r.name, "inviter" to m.name, "account" to account.name, "link" to "${props.baseUrl}/auth/invite#$token"),
            subjectArgs = arrayOf(m.name, account.name),
        )
        return views(listOf(r)).single()
    }

    fun load(id: UUID): MembershipsRecord = dsl.selectFrom(MEMBERSHIPS).where(MEMBERSHIPS.ID.eq(id)).fetchOne() ?: throw NotFoundException("Person")

    private fun apply(m: Member, r: MembershipsRecord, p: Patch<PersonInput>, creating: Boolean) {
        val i = p.value
        val errors = mutableMapOf<String, String>()
        i.name?.let { if (it.isBlank()) errors["name"] = "Enter a name" else Names.problem(it)?.let { e -> errors["name"] = e } ?: run { r.name = it.trim() } }
        i.email?.let {
            val email = it.trim().lowercase()
            if (!email.contains('@')) errors["email"] = "Enter a valid email address"
            else if (!creating && r.userId != null && email != r.email) errors["email"] = "People who have signed in change their email themselves"
            else {
                if (!creating && email != r.email) {
                    // Invitations already sent were for the old address; they stop working.
                    dsl.update(LOGIN_TOKENS).set(LOGIN_TOKENS.USED_AT, Instant.now(clock))
                        .where(LOGIN_TOKENS.MEMBERSHIP_ID.eq(r.id)).and(LOGIN_TOKENS.USED_AT.isNull).execute()
                    if (r.status == "invited") r.status = "pending_invite"
                }
                r.email = email
            }
        }
        i.role?.let { role ->
            if (role !in setOf("admin", "manager", "member")) {
                errors["role"] = "Role must be admin, manager or member"
            } else {
                if (!creating && r.role == "admin" && role != "admin") requireAnotherAdmin(r.id)
                r.role = role
                if (role == "admin") {
                    r.canSeeRates = true
                    r.canManageProjects = true
                    r.canManageInvoices = true
                } else if (creating && role == "manager") {
                    r.canManageProjects = true
                    r.canSeeRates = true
                }
            }
        }
        i.isActive?.let {
            if (!it && r.role == "admin") requireAnotherAdmin(r.id)
            if (!it && r.id == m.membershipId) errors["is_active"] = "You can't deactivate yourself"
            // Coming back with sign-in access (or an invitation) takes a seat again.
            if (it && !creating && !r.isActive && r.status in setOf("active", "invited")) seats.requireSeat(m.accountId)
            r.isActive = it
            r.archivedAt = if (it) null else (r.archivedAt ?: Instant.now(clock))
        }
        i.isContractor?.let { r.isContractor = it }
        i.isBillableDefault?.let { r.isBillableDefault = it }
        i.weeklyCapacitySeconds?.let { if (it < 0 || it > 7 * 86400) errors["weekly_capacity_seconds"] = "Enter a weekly capacity between 0 and 168 hours" else r.weeklyCapacitySeconds = it }
        i.hasAccessToAllFutureProjects?.let { r.hasAccessToAllFutureProjects = it }
        p.field("default_billable_rate", { defaultBillableRate }) { if (it != null && it < 0) errors["default_billable_rate"] = "Rates cannot be negative" else r.defaultBillableRate = it }
        p.field("cost_rate", { costRate }) { if (it != null && it < 0) errors["cost_rate"] = "Rates cannot be negative" else r.costRate = it }
        if (r.role != "admin") {
            i.canSeeRates?.let { r.canSeeRates = it }
            i.canManageProjects?.let { r.canManageProjects = it }
            i.canManageInvoices?.let { r.canManageInvoices = it }
        }
        if (errors.isNotEmpty()) throw ValidationException(errors)
    }

    private fun requireAnotherAdmin(exceptId: UUID) {
        val others = dsl.fetchCount(
            MEMBERSHIPS,
            MEMBERSHIPS.ROLE.eq("admin").and(MEMBERSHIPS.IS_ACTIVE.isTrue).and(MEMBERSHIPS.STATUS.eq("active")).and(MEMBERSHIPS.ID.ne(exceptId)),
        )
        if (others == 0) throw ConflictException("last_admin", "An account needs at least one active admin")
    }

    private fun setTeams(m: Member, membershipId: UUID, teamIds: List<UUID>) {
        val valid = dsl.select(TEAMS.ID).from(TEAMS).where(TEAMS.ID.`in`(teamIds)).fetchSet(TEAMS.ID)
        if (valid.size != teamIds.toSet().size) throw ValidationException("team_ids", "Unknown team")
        dsl.deleteFrom(TEAM_MEMBERSHIPS).where(TEAM_MEMBERSHIPS.MEMBERSHIP_ID.eq(membershipId)).and(TEAM_MEMBERSHIPS.TEAM_ID.notIn(valid)).execute()
        valid.forEach { teamId ->
            dsl.insertInto(TEAM_MEMBERSHIPS)
                .set(TEAM_MEMBERSHIPS.ACCOUNT_ID, m.accountId).set(TEAM_MEMBERSHIPS.TEAM_ID, teamId).set(TEAM_MEMBERSHIPS.MEMBERSHIP_ID, membershipId)
                .onConflictDoNothing().execute()
        }
    }

    private fun stopRunningTimer(membershipId: UUID) {
        val now = Instant.now(clock)
        dsl.selectFrom(TIME_ENTRIES).where(TIME_ENTRIES.MEMBERSHIP_ID.eq(membershipId)).and(TIME_ENTRIES.TIMER_STARTED_AT.isNotNull).fetch().forEach {
            val elapsed = java.time.Duration.between(it.timerStartedAt, now).seconds.coerceAtLeast(0)
            it.durationSeconds = (it.durationSeconds + elapsed).coerceAtMost(86400L).toInt()
            it.timerStartedAt = null
            it.store()
        }
    }

    private fun presentFields(i: PersonInput) = buildSet {
        if (i.defaultBillableRate != null) add("default_billable_rate")
        if (i.costRate != null) add("cost_rate")
    }

    private fun views(rows: List<MembershipsRecord>): List<PersonView> {
        if (rows.isEmpty()) return emptyList()
        val teams = dsl.select(TEAM_MEMBERSHIPS.MEMBERSHIP_ID, TEAM_MEMBERSHIPS.TEAM_ID).from(TEAM_MEMBERSHIPS)
            .where(TEAM_MEMBERSHIPS.MEMBERSHIP_ID.`in`(rows.map { it.id }))
            .fetch().groupBy({ it.value1() }) { it.value2() }
        val users = dsl.select(USERS.ID, USERS.NAME, USERS.TOTP_ENABLED_AT).from(USERS).where(USERS.ID.`in`(rows.mapNotNull { it.userId })).fetchMap(USERS.ID)
        val admin = Current.memberOrNull()?.isAdmin == true
        return rows.map { r ->
            PersonView(
                id = r.id, userId = r.userId, name = users[r.userId]?.value2() ?: r.name, email = r.email, role = r.role, status = r.status,
                isActive = r.isActive, isContractor = r.isContractor, isBillableDefault = r.isBillableDefault,
                weeklyCapacitySeconds = r.weeklyCapacitySeconds, hasAccessToAllFutureProjects = r.hasAccessToAllFutureProjects,
                defaultBillableRate = r.defaultBillableRate, costRate = r.costRate,
                canSeeRates = r.role == Role.ADMIN.sql || r.canSeeRates,
                canManageProjects = r.role == Role.ADMIN.sql || (r.role == Role.MANAGER.sql && r.canManageProjects),
                canManageInvoices = r.role == Role.ADMIN.sql || (r.role == Role.MANAGER.sql && r.canManageInvoices),
                teamIds = teams[r.id].orEmpty(), invitedAt = r.invitedAt, createdAt = r.createdAt, updatedAt = r.updatedAt,
                twoFactorEnabled = if (admin) users[r.userId]?.let { it.value3() != null } else null,
            )
        }
    }
}

@RestController
@RequestMapping("/api/v1/people")
@Tag(name = "people", description = "People in the account (memberships), invitations, roles and default rates")
class PeopleController(private val people: PeopleService, private val patches: Patches) {
    @GetMapping
    fun list(
        @RequestParam(name = "is_active", required = false) isActive: Boolean?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ) = people.list(Current.member(), isActive, cursor, limit)

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID) = people.get(Current.member(), id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody body: PersonInput) = people.create(Current.member(), body)

    @PatchMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @io.swagger.v3.oas.annotations.parameters.RequestBody(content = [Content(schema = Schema(implementation = PersonInput::class))]) @RequestBody body: JsonNode,
    ) = people.update(Current.member(), id, patches.parse(body))

    @PostMapping("/{id}/invite")
    fun invite(@PathVariable id: UUID) = people.invite(Current.member(), id)
}
