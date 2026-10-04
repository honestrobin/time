// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.db

import com.honestrobin.time.platform.HonestRobinProperties
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
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

    @Bean
    fun rlsSelfCheck(dsl: DSLContext, tx: TransactionTemplate) = ApplicationRunner { _: ApplicationArguments ->
        val log = LoggerFactory.getLogger("com.honestrobin.time.platform.db.RlsSelfCheck")
        val effective = tx.execute {
            dsl.fetchValue("select not (rolsuper or rolbypassrls) from pg_roles where rolname = current_user") as Boolean?
        }
        if (effective == true) {
            log.info("Row-level security is active for the application role")
        } else {
            log.warn("Row-level security is NOT effective: the database role bypasses RLS. Tenant isolation relies on application checks only.")
        }
    }
}
