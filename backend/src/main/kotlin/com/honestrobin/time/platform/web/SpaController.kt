// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.web

import org.springframework.core.io.ClassPathResource
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

/** Serves the SPA's index.html for client-side routes (everything that is not an API or static asset). */
@Controller
class SpaController {
    private val index = ClassPathResource("static/index.html")

    @GetMapping(
        "/",
        "/{path:^(?!api|v3|actuator|webhooks|assets)[^.]*}",
        "/{path:^(?!api|v3|actuator|webhooks|assets)[^.]*}/**",
    )
    fun index(): ResponseEntity<Any> {
        if (!index.exists()) {
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN)
                .body("Honest Robin: Time API is running. The web app has not been built into this binary (run the frontend build).")
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).cacheControl(CacheControl.noCache()).body(index.inputStream.readAllBytes())
    }
}
