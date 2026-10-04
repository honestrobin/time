// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.web

import io.swagger.v3.core.jackson.ModelResolver
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {
    /**
     * Makes the schema follow the API's snake_case JSON naming. swagger-core still reads models
     * with Jackson 2, so it gets its own mapper with the same naming as the app's Jackson 3 one.
     */
    @Bean
    fun modelResolver() = ModelResolver(
        io.swagger.v3.core.util.Json.mapper().copy().setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE),
    )

    @Bean
    fun openApi(): OpenAPI = OpenAPI()
        .info(
            Info().title("Honest Robin: Time API").version("v1")
                .description(
                    "Public REST API of Honest Robin: Time. Authenticate with a personal access token (`Authorization: Bearer hrt_…`). " +
                        "Session-authenticated browser clients send the `HonestRobin-Account-Id` header to pick an account.",
                )
                .license(License().name("AGPL-3.0-only").url("https://www.gnu.org/licenses/agpl-3.0.html")),
        )
        .components(Components().addSecuritySchemes("token", SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")))
        .addSecurityItem(SecurityRequirement().addList("token"))

    /** Stable operation ids (`<controller>_<method>`) so generated clients have readable names. */
    @Bean
    fun operationIds() = OperationCustomizer { operation, handler ->
        val controller = handler.beanType.simpleName.removeSuffix("Controller").replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()
        val method = handler.method.name.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()
        operation.operationId("${controller}_$method")
    }
}
