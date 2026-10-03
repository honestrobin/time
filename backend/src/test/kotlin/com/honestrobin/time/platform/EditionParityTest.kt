// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform

import com.honestrobin.time.HonestRobinApplication
import com.honestrobin.time.platform.edition.EDITION_SPECIFIC_PACKAGES
import com.honestrobin.time.support.TestDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.io.File

/**
 * AT-0.2: the edition flag toggles only the allowed modules (trust charter §1.2.3).
 * Boots the application in both editions and compares every HTTP endpoint and bean.
 */
class EditionParityTest {

    @Test
    fun `editions expose the same endpoints except cloud billing`() {
        val selfhost = endpoints("selfhost")
        val cloud = endpoints("cloud")

        assertThat(selfhost - cloud).describedAs("Self-host must not have anything cloud lacks").isEmpty()
        val cloudOnly = cloud - selfhost
        assertThat(cloudOnly).allSatisfy { endpoint ->
            assertThat(endpoint.handlerPackage).isIn(EDITION_SPECIFIC_PACKAGES)
        }
        assertThat(cloudOnly.map { it.path }).allMatch { it.startsWith("/api/v1/billing") || it.startsWith("/webhooks/paddle") }
    }

    @Test
    fun `only allowlisted packages refer to the edition`() {
        val root = File("src/main/kotlin")
        val markers = listOf("Edition.CLOUD", "Edition.SELFHOST", "CloudEditionOnly", ".isCloud", "props.edition", "honestrobin.edition")
        val allowedDirs = EDITION_SPECIFIC_PACKAGES.map { File(root, it.replace('.', '/')).path }
        val offenders = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f -> allowedDirs.none { f.path.startsWith(it) } }
            .filter { f -> f.path != File(root, "com/honestrobin/time/platform/HonestRobinProperties.kt").path }
            .filter { f -> markers.any { f.readText().contains(it) } }
            .map { it.path }
            .toList()
        assertThat(offenders).describedAs("Product code must not branch on the edition").isEmpty()
    }

    data class Endpoint(val path: String, val methods: String, val handlerPackage: String)

    private fun endpoints(edition: String): Set<Endpoint> {
        val context: ConfigurableApplicationContext = SpringApplicationBuilder(HonestRobinApplication::class.java)
            .profiles("test")
            .run(
                "--honestrobin.edition=$edition",
                "--server.port=0",
                "--db-scheduler.enabled=false",
                "--spring.datasource.url=${TestDatabase.sharedUrl}",
                "--spring.datasource.username=${TestDatabase.username}",
                "--spring.datasource.password=${TestDatabase.password}",
            )
        try {
            val mapping = context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping::class.java)
            return mapping.handlerMethods.map { (info, method) ->
                Endpoint(
                    path = info.pathPatternsCondition?.patternValues?.sorted()?.joinToString() ?: info.toString(),
                    methods = info.methodsCondition.methods.sorted().joinToString(),
                    handlerPackage = method.beanType.packageName,
                )
            }.toSet()
        } finally {
            context.close()
        }
    }
}
