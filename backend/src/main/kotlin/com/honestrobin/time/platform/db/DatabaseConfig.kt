// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.db

import com.honestrobin.time.platform.HonestRobinProperties
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.jooq.autoconfigure.DefaultConfigurationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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
     * Runs once every bean exists: after the migrations, and before the web server takes
     * requests, so an app that can't keep accounts apart never serves one. Jobs may already have
     * started; without the role, their transactions can't begin either.
     */
    @Bean
    fun rowLevelSecurityCheck(dataSource: DataSource, transactions: PlatformTransactionManager, props: HonestRobinProperties) =
        SmartInitializingSingleton { RowLevelSecurityCheck(dataSource, transactions, props.db.appRole).verify() }
}
