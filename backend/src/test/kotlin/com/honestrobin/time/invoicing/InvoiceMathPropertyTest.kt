// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.invoicing

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.orNull
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.math.abs

class InvoiceMathPropertyTest {
    private fun property(block: suspend () -> Unit) = runBlocking { block() }

    private val percent = Arb.int(0..10_000).map { BigDecimal.valueOf(it.toLong(), 2) }
    private val line = Arb.bind(Arb.int(0..100_000), Arb.long(0L..10_000_000L), Arb.boolean(), Arb.boolean(), Arb.element("S", "Z", "E", "AE"), Arb.element(0, 5, 10, 20, 25)) { q, p, t1, t2, cat, rate ->
        MathLine(BigDecimal.valueOf(q.toLong(), 2), p, t1, t2, cat, BigDecimal(if (cat == "S") rate else 0))
    }

    @Test
    fun `the total is always subtotal minus discount plus taxes, and nothing is negative`() = property {
        checkAll(Arb.list(line, 0..12), percent.orNull(), percent.orNull(), percent.orNull(), Arb.boolean()) { lines, discount, t1, t2, vat ->
            val t = InvoiceMath.totals(lines, discount, t1, t2, vat)
            assertThat(t.total).isEqualTo(t.subtotal - t.discount + t.tax1 + t.tax2)
            assertThat(t.subtotal).isEqualTo(t.lineTotals.sum())
            assertThat(t.discount).isBetween(0L, t.subtotal)
            assertThat(t.tax1).isNotNegative()
            assertThat(t.tax2).isNotNegative()
            assertThat(t.total).isNotNegative()
        }
    }

    @Test
    fun `each line total is within half a minor unit of quantity times price`() = property {
        checkAll(line) { l ->
            val exact = l.quantity.multiply(BigDecimal.valueOf(l.unitPrice))
            assertThat(BigDecimal.valueOf(InvoiceMath.lineTotal(l.quantity, l.unitPrice)).subtract(exact).abs()).isLessThanOrEqualTo(BigDecimal("0.5"))
        }
    }

    @Test
    fun `the VAT breakdown covers every line once, and its bases add up to the discounted subtotal give or take rounding`() = property {
        checkAll(Arb.list(line, 1..12), percent.orNull()) { lines, discount ->
            val t = InvoiceMath.totals(lines, discount, null, null, vatCategories = true)
            assertThat(t.tax1).isEqualTo(t.vat.sumOf { it.tax })
            assertThat(t.vat.map { it.category to it.percent }).doesNotHaveDuplicates()
            assertThat(abs(t.vat.sumOf { it.base } - (t.subtotal - t.discount))).isLessThanOrEqualTo(t.vat.size.toLong())
            t.vat.filter { it.category != "S" }.forEach { assertThat(it.tax).isZero() }
        }
    }

    @Test
    fun `a higher tax rate never gives less tax`() = property {
        checkAll(Arb.list(line, 1..8), percent, percent) { lines, a, b ->
            val low = InvoiceMath.totals(lines, null, minOf(a, b), null, false).tax1
            val high = InvoiceMath.totals(lines, null, maxOf(a, b), null, false).tax1
            assertThat(low).isLessThanOrEqualTo(high)
        }
    }
}
