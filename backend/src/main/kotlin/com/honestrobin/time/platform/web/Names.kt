// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.web

/**
 * Names of people and workspaces end up in emails we send to others ("Ada invited you to Acme"),
 * so they stay names: short, and without links someone could use to send spam in our name.
 */
object Names {
    private val LINK = Regex("""(?i)(://|www\.|\bhttps?\b)""")

    /** An error message for [value] as a name, or null when it's fine. */
    fun problem(value: String, max: Int = 100): String? = when {
        value.isBlank() -> null
        value.length > max -> "Use at most $max characters"
        LINK.containsMatchIn(value) -> "A name can't contain a web address"
        else -> null
    }
}
