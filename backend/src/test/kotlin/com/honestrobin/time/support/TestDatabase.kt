// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.support

import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * One Postgres container per test JVM. Set HONESTROBIN_TEST_JDBC_URL (user/password `postgres`)
 * to use an existing server instead. Each call to [freshDatabase] creates an empty database.
 */
object TestDatabase {
    private val external = System.getenv("HONESTROBIN_TEST_JDBC_URL")
    private val counter = AtomicInteger()

    private val container: PostgreSQLContainer<*>? by lazy {
        if (external != null) null else PostgreSQLContainer("postgres:16-alpine").withCommand("postgres", "-c", "max_connections=300", "-c", "fsync=off").also { it.start() }
    }

    val username: String get() = container?.username ?: System.getenv("HONESTROBIN_TEST_JDBC_USER") ?: "postgres"
    val password: String get() = container?.password ?: System.getenv("HONESTROBIN_TEST_JDBC_PASSWORD") ?: "postgres"

    private val baseUrl: String get() = external ?: container!!.jdbcUrl

    /** The shared database most tests use; tenants isolate them from each other. */
    val sharedUrl: String by lazy { freshDatabase("shared") }

    fun freshDatabase(prefix: String = "t"): String {
        val name = "${prefix}_${ProcessHandle.current().pid()}_${counter.incrementAndGet()}"
        DriverManager.getConnection(baseUrl, username, password).use { it.createStatement().execute("create database $name") }
        return baseUrl.replaceAfterLast("/", name).substringBefore("?").let { if (baseUrl.contains("?")) it + "?" + baseUrl.substringAfter("?") else it }
    }
}
