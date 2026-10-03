// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.web

import org.springframework.core.io.ClassPathResource
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import java.time.Duration

/**
 * The browser extension's selector config (where the Track time button goes on each site). The
 * extension uses this copy when it is newer than the one it shipped with, so a site redesign can be
 * fixed by updating the instance instead of waiting for a store review.
 */
@Controller
class ExtensionConfigController {
    private val selectors = ClassPathResource("extension/selectors.json")

    @GetMapping("/extension/selectors.json")
    fun selectors(): ResponseEntity<ByteArray> =
        if (!selectors.exists()) {
            ResponseEntity.notFound().build()
        } else {
            ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(selectors.inputStream.readAllBytes())
        }
}
