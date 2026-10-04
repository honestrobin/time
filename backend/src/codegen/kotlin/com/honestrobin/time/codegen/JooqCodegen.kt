// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.codegen

import org.flywaydb.core.Flyway
import org.jooq.codegen.GenerationTool
import org.jooq.meta.jaxb.Configuration
import org.jooq.meta.jaxb.Database
import org.jooq.meta.jaxb.ForcedType
import org.jooq.meta.jaxb.Generate
import org.jooq.meta.jaxb.Generator
import org.jooq.meta.jaxb.Jdbc
import org.jooq.meta.jaxb.Target
import org.testcontainers.postgresql.PostgreSQLContainer
import java.io.File

/**
 * Regenerates `src/generated/jooq` from the Flyway migrations.
 * Uses JOOQ_JDBC_URL if set, otherwise a throwaway Testcontainers Postgres.
 */
fun main() {
    val explicitUrl = System.getenv("JOOQ_JDBC_URL")
    val container = if (explicitUrl == null) PostgreSQLContainer("postgres:18-alpine").also { it.start() } else null
    try {
        val url = explicitUrl ?: container!!.jdbcUrl
        val user = System.getenv("JOOQ_JDBC_USER") ?: container?.username ?: "postgres"
        val password = System.getenv("JOOQ_JDBC_PASSWORD") ?: container?.password ?: ""

        Flyway.configure()
            .dataSource(url, user, password)
            .locations("filesystem:src/main/resources/db/migration")
            .initSql("set honestrobin.rls_bypass = 'on'")
            .cleanDisabled(false)
            .load()
            .also { if (explicitUrl != null) it.clean() }
            .migrate()

        val target = File("src/generated/jooq")
        target.deleteRecursively()

        GenerationTool.generate(
            Configuration()
                .withJdbc(Jdbc().withDriver("org.postgresql.Driver").withUrl(url).withUser(user).withPassword(password))
                .withGenerator(
                    Generator()
                        .withDatabase(
                            Database()
                                .withName("org.jooq.meta.postgres.PostgresDatabase")
                                .withInputSchema("public")
                                .withExcludes("flyway_schema_history|scheduled_tasks|uuid_generate_v7|honestrobin_.*")
                                .withForcedTypes(
                                    ForcedType().withName("INSTANT").withIncludeTypes("(?i:timestamp\\ with\\ time\\ zone|timestamptz)"),
                                ),
                        )
                        .withGenerate(
                            Generate()
                                .withJavaTimeTypes(true)
                                .withRecords(true)
                                .withPojos(false)
                                .withDaos(false)
                                .withFluentSetters(true)
                                .withRoutines(false)
                                .withSequences(false)
                                .withIndexes(false),
                        )
                        .withTarget(Target().withPackageName("com.honestrobin.time.db").withDirectory(target.path)),
                ),
        )
    } finally {
        container?.stop()
    }
}
