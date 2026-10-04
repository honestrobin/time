// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.web

import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverter
import io.swagger.v3.core.converter.ModelConverterContext
import io.swagger.v3.core.jackson.ModelResolver
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {
    /**
     * Makes the schema follow the API's JSON: snake_case names, `is_…` booleans, and non-null
     * Kotlin properties as required. swagger-core still reads models with Jackson 2, so it gets a
     * Jackson 2 mapper set up like the app's Jackson 3 one.
     */
    @Bean
    fun modelResolver() = ModelResolver(
        io.swagger.v3.core.util.Json.mapper().copy()
            .registerModule(com.fasterxml.jackson.module.kotlin.KotlinModule.Builder().build())
            .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE),
    )

    /**
     * swagger-core knows Jackson 2's JsonNode as "any JSON" but not Jackson 3's, which it would
     * describe as an object full of `is…` flags. Ours is described the way Jackson 2's is.
     */
    @Bean
    fun anyJsonForJackson3Nodes() = object : ModelConverter {
        override fun resolve(type: AnnotatedType, context: ModelConverterContext, chain: Iterator<ModelConverter>): Schema<*>? {
            val raw = type.type?.let { io.swagger.v3.core.util.Json.mapper().constructType(it).rawClass }
            val resolved = if (raw != null && tools.jackson.databind.JsonNode::class.java.isAssignableFrom(raw)) {
                type.type(com.fasterxml.jackson.databind.JsonNode::class.java)
            } else {
                type
            }
            return if (chain.hasNext()) chain.next().resolve(resolved, context, chain) else null
        }
    }

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
