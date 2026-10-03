// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.accounts

import com.honestrobin.time.auth.AuthService
import com.honestrobin.time.db.Tables.USERS
import com.honestrobin.time.platform.HonestRobinProperties
import com.honestrobin.time.platform.security.Current
import com.honestrobin.time.platform.web.ApiException
import io.swagger.v3.oas.annotations.tags.Tag
import org.jooq.DSLContext
import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class CreatedAccount(val id: UUID)

@RestController
@RequestMapping("/api/v1")
@Tag(name = "account", description = "Account (workspace) settings")
class AccountController(
    private val accounts: AccountService,
    private val auth: AuthService,
    private val props: HonestRobinProperties,
    private val dsl: DSLContext,
) {
    @GetMapping("/account")
    fun get(): AccountView = accounts.get(Current.member())

    @PatchMapping("/account")
    fun update(@RequestBody body: AccountUpdate): AccountView = accounts.update(Current.member(), body)

    /** Creates an additional account for the signed-in user (e.g. a second company). */
    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    fun create(@RequestBody body: NewAccount): CreatedAccount {
        val userId = Current.principal().userId
        val instanceAdmin = dsl.select(USERS.IS_INSTANCE_ADMIN).from(USERS).where(USERS.ID.eq(userId)).fetchOne()!!.value1()
        if (props.signupMode != HonestRobinProperties.SignupMode.OPEN && !instanceAdmin) {
            throw ApiException(HttpStatus.FORBIDDEN, "signup_closed", "Only the instance admin can create accounts on this instance")
        }
        return CreatedAccount(accounts.create(userId, body))
    }
}
