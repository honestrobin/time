// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.features

import com.honestrobin.time.platform.HonestRobinProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * The parts of Time that are built but switched off by default, while the core is made a joy to
 * use first (the maintainer's decision of 9 October 2026). Each comes back on its own when it's
 * switched on. Switched off, a part leaves the web app and stops what it would do unasked, since
 * nobody could see it or turn it off: the lock on a week sent for approval, timesheet and invoice
 * reminders, budget alerts, a Harvest sync (SwitchedOffTest). Approved time stays locked, and an
 * approved week can take new entries while approvals are off. Its data stays, and so do the export
 * and Move out; the API refuses sending a week for approval while approvals are off. Both
 * editions have the same switches.
 */
enum class Feature(val key: String) {
    INVOICES("invoices"),
    PAYMENTS("payments"),
    ACCOUNTING("accounting"),
    EXPENSES("expenses"),
    TASKS("tasks"),
    TEAM("team"),
    APPROVALS("approvals"),
    BUDGETS("budgets"),
    IMPORT("import"),
    ;

    companion object {
        /** Switches every feature on. */
        const val ALL = "all"

        /** Reads `honestrobin.features`: names separated by commas, `all`, or nothing. */
        fun parse(names: Collection<String>): Parsed {
            val requested = names.flatMap { it.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            val known = entries.associateBy { it.key }
            val on = if (ALL in requested) entries.toSet() else requested.mapNotNull { known[it] }.toSet()
            return Parsed(on, (requested - known.keys - ALL).sorted())
        }
    }

    data class Parsed(val on: Set<Feature>, val unknown: List<String>)
}

@Component
class Features(props: HonestRobinProperties) {
    private val parsed = Feature.parse(props.features)

    init {
        if (parsed.unknown.isNotEmpty()) {
            LoggerFactory.getLogger(javaClass).warn(
                "Ignoring unknown feature switches {}; known: {}, or {}",
                parsed.unknown, Feature.entries.map { it.key }, Feature.ALL,
            )
        }
    }

    /** The names of the switched-on features, sorted. */
    val on: List<String> = parsed.on.map { it.key }.sorted()

    fun isOn(feature: Feature) = feature in parsed.on
}
