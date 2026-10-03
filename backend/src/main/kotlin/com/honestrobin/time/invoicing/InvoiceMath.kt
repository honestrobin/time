// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.invoicing

import java.math.BigDecimal
import java.math.RoundingMode

/** What the totals of an invoice depend on, for one line. */
data class MathLine(
    val quantity: BigDecimal,
    /** Minor units. */
    val unitPrice: Long,
    val tax1: Boolean = false,
    val tax2: Boolean = false,
    /** UNCL 5305 code (S, Z, E, AE, K, G, O) when the invoice uses VAT categories. */
    val vatCategory: String? = null,
    val vatPercent: BigDecimal? = null,
)

/** One row of the VAT breakdown (EN 16931 BG-23): the taxable base and tax per category and rate. */
data class VatGroup(val category: String, val percent: BigDecimal, val base: Long, val tax: Long)

data class Totals(
    val lineTotals: List<Long>,
    val subtotal: Long,
    val discount: Long,
    val tax1: Long,
    val tax2: Long,
    val total: Long,
    val vat: List<VatGroup>,
)

/**
 * Invoice arithmetic (spec §5.5). Line totals are quantity × unit price, half-up to minor units.
 * A discount applies to the subtotal and, in proportion, to every taxable base. Taxes are either
 * up to two named percentages on the lines marked for them (Harvest's model, and the one that
 * fits sales tax and GST), or, when the invoice uses VAT categories, one rate per category,
 * computed on each category's base and rounded once per category, as EN 16931 does.
 */
object InvoiceMath {
    fun lineTotal(quantity: BigDecimal, unitPrice: Long): Long =
        quantity.multiply(BigDecimal.valueOf(unitPrice)).setScale(0, RoundingMode.HALF_UP).longValueExact()

    fun percentOf(amount: Long, percent: BigDecimal?): Long =
        if (percent == null || percent.signum() == 0) 0
        else BigDecimal.valueOf(amount).multiply(percent).divide(BigDecimal(100), 0, RoundingMode.HALF_UP).longValueExact()

    fun totals(lines: List<MathLine>, discountPercent: BigDecimal?, tax1Percent: BigDecimal?, tax2Percent: BigDecimal?, vatCategories: Boolean): Totals {
        val lineTotals = lines.map { lineTotal(it.quantity, it.unitPrice) }
        val subtotal = lineTotals.sum()
        val discount = percentOf(subtotal, discountPercent)
        fun afterDiscount(base: Long) = base - percentOf(base, discountPercent)

        if (vatCategories) {
            val groups = lines.indices.groupBy { (lines[it].vatCategory ?: "S") to (lines[it].vatPercent ?: BigDecimal.ZERO).stripTrailingZeros() }
                .map { (key, idx) ->
                    val base = afterDiscount(idx.sumOf { lineTotals[it] })
                    VatGroup(key.first, key.second, base, percentOf(base, key.second))
                }
                .sortedWith(compareBy({ it.category }, { it.percent }))
            val vat = groups.sumOf { it.tax }
            return Totals(lineTotals, subtotal, discount, vat, 0, subtotal - discount + vat, groups)
        }
        val tax1 = percentOf(afterDiscount(lines.indices.filter { lines[it].tax1 }.sumOf { lineTotals[it] }), tax1Percent)
        val tax2 = percentOf(afterDiscount(lines.indices.filter { lines[it].tax2 }.sumOf { lineTotals[it] }), tax2Percent)
        return Totals(lineTotals, subtotal, discount, tax1, tax2, subtotal - discount + tax1 + tax2, emptyList())
    }

    /** Categories a line can carry, with whether they take a rate above zero. */
    val VAT_CATEGORIES = mapOf("S" to true, "Z" to false, "E" to false, "AE" to false, "K" to false, "G" to false, "O" to false)
}
