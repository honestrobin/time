// SPDX-License-Identifier: AGPL-3.0-only
import fc from "fast-check";
import { describe, expect, it } from "vitest";
import { invoiceTotals } from "./invoiceMath";

describe("invoiceTotals", () => {
  it("matches the server's worked example for two named taxes and a discount", () => {
    const t = invoiceTotals(
      [
        { quantity: 1.5, unitPrice: 100_000, tax1: true, tax2: true },
        { quantity: 3, unitPrice: 3_333, tax1: true, tax2: false },
      ],
      5,
      10,
      2.5,
      false,
    );
    expect([t.subtotal, t.discount, t.tax1, t.tax2, t.total]).toEqual([159_999, 8_000, 15_200, 3_563, 159_999 - 8_000 + 15_200 + 3_563]);
  });

  it("matches the server's VAT example, taxed per category", () => {
    const t = invoiceTotals(
      [
        { quantity: 10, unitPrice: 12_345, tax1: false, tax2: false, vatCategory: "S", vatPercent: 20 },
        { quantity: 1, unitPrice: 4_999, tax1: false, tax2: false, vatCategory: "S", vatPercent: 5 },
        { quantity: 1, unitPrice: 10_000, tax1: false, tax2: false, vatCategory: "Z", vatPercent: 0 },
      ],
      null,
      null,
      null,
      true,
    );
    expect(t.tax1).toBe(24_940);
    expect(t.vat.map((g) => `${g.category}/${g.percent}`)).toEqual(["S/5", "S/20", "Z/0"]);
  });

  it("always adds up: total = subtotal - discount + taxes", () => {
    const line = fc.record({
      quantity: fc.integer({ min: 0, max: 100_000 }).map((q) => q / 100),
      unitPrice: fc.integer({ min: 0, max: 10_000_000 }),
      tax1: fc.boolean(),
      tax2: fc.boolean(),
    });
    fc.assert(
      fc.property(fc.array(line, { maxLength: 10 }), fc.option(fc.integer({ min: 0, max: 10_000 }).map((p) => p / 100)), (lines, discount) => {
        const t = invoiceTotals(lines, discount ?? null, 20, 5, false);
        expect(t.total).toBe(t.subtotal - t.discount + t.tax1 + t.tax2);
        expect(t.total).toBeGreaterThanOrEqual(0);
      }),
    );
  });
});
