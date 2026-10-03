// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.edition

import com.honestrobin.time.platform.HonestRobinProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.stereotype.Component

/** Edition-dependent presentation details. Product behaviour must not branch on these. */
@Component
class EditionInfo(private val props: HonestRobinProperties, build: ObjectProvider<BuildProperties>) {
    val version: String = build.ifAvailable?.version ?: "dev"

    val isCloud: Boolean get() = props.edition == Edition.CLOUD

    val name: String get() = props.edition.name.lowercase()

    /** Marketing links are shown in the cloud edition only. */
    val marketingUrl: String? get() = if (isCloud) "https://honestrobin.com" else null
}
