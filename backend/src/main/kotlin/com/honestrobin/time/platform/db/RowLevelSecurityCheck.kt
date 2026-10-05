// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.db

import org.slf4j.LoggerFactory
import org.springframework.boot.diagnostics.FailureAnalyzedException
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource

/**
 * Checks, before the app serves anyone, that row-level security keeps accounts apart: that each
 * transaction can switch to [appRole] and that the role it then runs as doesn't bypass the
 * policies. If it can't hold, for example after a restore into a database where the role wasn't
 * created first, the app refuses to start rather than leave every account's data to each query's
 * own filter (decision record 0026).
 */
class RowLevelSecurityCheck(
    private val dataSource: DataSource,
    private val transactions: PlatformTransactionManager,
    private val appRole: String,
) {
    fun verify() {
        if (appRole.isNotBlank()) {
            val (user, exists, member) = dataSource.connection.use { con ->
                con.prepareStatement(
                    "select current_user, exists (select 1 from pg_roles where rolname = ?), " +
                        "case when exists (select 1 from pg_roles where rolname = ?) then pg_has_role(current_user, ?, 'SET') else false end",
                ).use { ps ->
                    ps.setString(1, appRole)
                    ps.setString(2, appRole)
                    ps.setString(3, appRole)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        Triple(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3))
                    }
                }
            }
            if (!exists) {
                throw RowLevelSecurityNotEffective(
                    "Refusing to start: the database role '$appRole' doesn't exist, so row-level security can't keep accounts apart. " +
                        "This happens after a restore without the role, or when the database user may not create roles.",
                    "Create the role (CREATE ROLE $appRole NOLOGIN;) and give it its rights, then start the app again. " +
                        "See docs/self-host.md, Database role.",
                )
            }
            if (!member) {
                throw RowLevelSecurityNotEffective(
                    "Refusing to start: the database user '$user' may not switch to the role '$appRole', so row-level security can't keep accounts apart.",
                    "Run GRANT $appRole TO $user; as a database user allowed to. See docs/self-host.md, Database role.",
                )
            }
        }
        // As the app runs every transaction: switched to the role, if one is configured.
        val (role, bypasses, granted) = TransactionTemplate(transactions).execute {
            val con = DataSourceUtils.getConnection(dataSource)
            con.prepareStatement(
                "select current_user, rolsuper or rolbypassrls, coalesce(has_table_privilege(to_regclass('accounts'), 'SELECT'), false) from pg_roles where rolname = current_user",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    Triple(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3))
                }
            }
        }!!
        if (bypasses && appRole.isBlank()) {
            throw RowLevelSecurityNotEffective(
                "Refusing to start: honestrobin.db.app-role is blank, so transactions run as the database user '$role', " +
                    "which bypasses row-level security, so it can't keep accounts apart.",
                "Remove the honestrobin.db.app-role setting, so that the app switches to its own role, honestrobin_app. " +
                    "See docs/self-host.md, Database role.",
            )
        }
        if (bypasses) {
            throw RowLevelSecurityNotEffective(
                "Refusing to start: transactions run as the database role '$role', which bypasses row-level security, so it can't keep accounts apart.",
                "Run ALTER ROLE $role NOSUPERUSER NOBYPASSRLS; as a database superuser. See docs/self-host.md, Database role.",
            )
        }
        if (!granted) {
            throw RowLevelSecurityNotEffective(
                "Refusing to start: the database role '$role' has no rights on the app's tables. " +
                    "This happens when the role was created after the tables, for example after a restore without it.",
                "Give it its rights, then start the app again. See docs/self-host.md, Database role.",
            )
        }
        log.info("Row-level security is active for the application role")
    }

    private companion object {
        private val log = LoggerFactory.getLogger(RowLevelSecurityCheck::class.java)
    }
}

/** Spring Boot reports it as the reason the app didn't start, with the fix, and without a stack trace. */
class RowLevelSecurityNotEffective(description: String, action: String) : FailureAnalyzedException(description, action)
