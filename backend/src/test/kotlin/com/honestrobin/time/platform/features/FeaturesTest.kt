// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.features

import com.honestrobin.time.platform.HonestRobinProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FeaturesTest {

    private fun on(vararg names: String) = Features(HonestRobinProperties(features = names.toList())).on

    @Test
    fun `everything beyond the core is off unless switched on`() {
        assertThat(on()).isEmpty()
        assertThat(Features(HonestRobinProperties()).isOn(Feature.INVOICES)).isFalse()
    }

    @Test
    fun `switches are read by name, in any case, from a list or one comma-separated value`() {
        assertThat(on("Invoices", " team ")).containsExactly("invoices", "team")
        assertThat(on("expenses,budgets")).containsExactly("budgets", "expenses")
    }

    @Test
    fun `all switches every feature on`() {
        assertThat(on("all")).containsExactlyInAnyOrderElementsOf(Feature.entries.map { it.key })
    }

    @Test
    fun `an unknown name is ignored and the known ones still apply`() {
        val parsed = Feature.parse(listOf("invoices", "invoicing"))
        assertThat(parsed.on).containsExactly(Feature.INVOICES)
        assertThat(parsed.unknown).containsExactly("invoicing")
    }
}
