// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.db

import com.honestrobin.time.platform.HonestRobinProperties
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer
import org.springframework.boot.jdbc.DataSourceBuilder
import org.springframework.boot.jooq.autoconfigure.DefaultConfigurationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource

@Configuration
@EnableTransactionManagement
class DatabaseConfig {
    @Bean
    fun transactionManager(dataSource: DataSource, props: HonestRobinProperties): PlatformTransactionManager =
        TenantAwareTransactionManager(dataSource, props.db.appRole)

    @Bean
    fun transactionTemplate(tm: PlatformTransactionManager) = TransactionTemplate(tm)

    @Bean
    fun jooqCustomizer() = DefaultConfigurationCustomizer { c ->
        c.settings()
            .withRenderSchema(false)
            // store() refreshes DB defaults (created_at, flags) into the record
            .withReturnAllOnUpdatableRecord(true)
            .withExecuteLogging(false)
    }

    /**
     * The migrations get connections of their own, opened for them and closed after them, never
     * the app's pool. They run with row-level security's bypass switched on for the whole session
     * (spring.flyway.init-sqls), and a pool doesn't reset that when it takes a connection back: a
     * query outside a transaction on it would have bypassed the policies until the pool retired
     * the connection, 30 minutes after it was opened.
     */
    @Bean
    fun migrationsOnConnectionsOfTheirOwn() = FlywayConfigurationCustomizer { flyway ->
        flyway.dataSource(DataSourceBuilder.derivedFrom(flyway.dataSource).type(SimpleDriverDataSource::class.java).build())
    }

    /**
     * Runs once every bean exists: after the migrations, and before the web server takes
     * requests, so an app that can't keep accounts apart never serves one. Jobs start after it
     * (db-scheduler.delay-startup-until-context-ready).
     */
    @Bean
    fun rowLevelSecurityCheck(dataSource: DataSource, transactions: PlatformTransactionManager, props: HonestRobinProperties) =
        SmartInitializingSingleton { RowLevelSecurityCheck(dataSource, transactions, props.db.appRole).verify() }
}
