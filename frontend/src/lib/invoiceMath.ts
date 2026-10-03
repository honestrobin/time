// SPDX-License-Identifier: AGPL-3.0-only
// The same arithmetic as the server's InvoiceMath, so the editor shows live totals before saving.

export interface MathLine {
  quantity: number;
  unitPrice: number;
  tax1: boolean;
  tax2: boolean;
  vatCategory?: string | null;
  vatPercent?: number | null;
}

export interface VatGroup {
  category: string;
  percent: number;
  base: number;
  tax: number;
}

export interface Totals {
  lineTotals: number[];
  subtotal: number;
  discount: number;
  tax1: number;
  tax2: number;
  total: number;
  vat: VatGroup[];
}

/** Half-up rounding to an integer, symmetric for negative values like Java's HALF_UP. */
export function roundHalfUp(x: number): number {
  const r = Math.round(Math.abs(x) + 1e-9);
  return x < 0 ? -r : r;
}

export const lineTotal = (quantity: number, unitPrice: number) => roundHalfUp(quantity * unitPrice);

export const percentOf = (amount: number, percent: number | null | undefined) => (!percent ? 0 : roundHalfUp((amount * percent) / 100));

export function invoiceTotals(lines: MathLine[], discountPercent: number | null, tax1: number | null, tax2: number | null, vatCategories: boolean): Totals {
  const lineTotals = lines.map((l) => lineTotal(l.quantity, l.unitPrice));
  const subtotal = lineTotals.reduce((a, b) => a + b, 0);
  const discount = percentOf(subtotal, discountPercent);
  const afterDiscount = (base: number) => base - percentOf(base, discountPercent);
  if (vatCategories) {
    const groups = new Map<string, { category: string; percent: number; base: number }>();
    lines.forEach((l, i) => {
      const category = l.vatCategory ?? "S";
      const percent = l.vatPercent ?? 0;
      const key = `${category}|${percent}`;
      const g = groups.get(key) ?? { category, percent, base: 0 };
      g.base += lineTotals[i];
      groups.set(key, g);
    });
    const vat = [...groups.values()]
      .map((g) => {
        const base = afterDiscount(g.base);
        return { category: g.category, percent: g.percent, base, tax: percentOf(base, g.percent) };
      })
      .sort((a, b) => a.category.localeCompare(b.category) || a.percent - b.percent);
    const tax = vat.reduce((a, g) => a + g.tax, 0);
    return { lineTotals, subtotal, discount, tax1: tax, tax2: 0, total: subtotal - discount + tax, vat };
  }
  const base = (pick: (l: MathLine) => boolean) => afterDiscount(lines.reduce((a, l, i) => (pick(l) ? a + lineTotals[i] : a), 0));
  const t1 = percentOf(base((l) => l.tax1), tax1);
  const t2 = percentOf(base((l) => l.tax2), tax2);
  return { lineTotals, subtotal, discount, tax1: t1, tax2: t2, total: subtotal - discount + t1 + t2, vat: [] };
}
