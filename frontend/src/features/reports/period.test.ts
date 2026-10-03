// SPDX-License-Identifier: AGPL-3.0-only
import fc from "fast-check";
import { describe, expect, it } from "vitest";
import { daysBetween, isCurrent, periodFor, shift, UNITS, type Unit } from "./period";

describe("report periods", () => {
  it("covers the week, month, quarter and year around a date", () => {
    expect(periodFor("week", "2026-10-03", 1)).toEqual({ from: "2026-09-28", to: "2026-10-04" });
    expect(periodFor("week", "2026-10-03", 7)).toEqual({ from: "2026-09-27", to: "2026-10-03" });
    expect(periodFor("month", "2026-02-14", 1)).toEqual({ from: "2026-02-01", to: "2026-02-28" });
    expect(periodFor("month", "2028-02-14", 1)).toEqual({ from: "2028-02-01", to: "2028-02-29" });
    expect(periodFor("quarter", "2026-11-30", 1)).toEqual({ from: "2026-10-01", to: "2026-12-31" });
    expect(periodFor("year", "2026-06-01", 1)).toEqual({ from: "2026-01-01", to: "2026-12-31" });
    expect(periodFor("all", "2026-06-01", 1)).toEqual({});
  });

  it("steps across year ends", () => {
    expect(shift("month", periodFor("month", "2026-12-05", 1), 1, 1)).toEqual({ from: "2027-01-01", to: "2027-01-31" });
    expect(shift("month", periodFor("month", "2026-01-05", 1), -1, 1)).toEqual({ from: "2025-12-01", to: "2025-12-31" });
    expect(shift("quarter", periodFor("quarter", "2026-02-01", 1), -1, 1)).toEqual({ from: "2025-10-01", to: "2025-12-31" });
    expect(shift("week", periodFor("week", "2026-12-30", 1), 1, 1)).toEqual({ from: "2027-01-04", to: "2027-01-10" });
    expect(shift("custom", { from: "2026-09-01", to: "2026-09-10" }, 1, 1)).toEqual({ from: "2026-09-11", to: "2026-09-20" });
  });

  it("knows the current period", () => {
    expect(isCurrent("month", { from: "2026-10-01", to: "2026-10-31" }, "2026-10-03", 1)).toBe(true);
    expect(isCurrent("month", { from: "2026-09-01", to: "2026-09-30" }, "2026-10-03", 1)).toBe(false);
  });

  const date = fc.date({ min: new Date("1990-01-01"), max: new Date("2090-12-31"), noInvalidDate: true }).map((d) => d.toISOString().slice(0, 10));
  const unit = fc.constantFrom<Unit>(...UNITS.filter((u) => u !== "all" && u !== "custom"));

  it("a period contains its anchor, and stepping forth and back returns to it", () => {
    fc.assert(
      fc.property(date, unit, fc.integer({ min: 1, max: 7 }), (anchor, u, weekStart) => {
        const p = periodFor(u, anchor, weekStart);
        expect(p.from! <= anchor && anchor <= p.to!).toBe(true);
        expect(shift(u, shift(u, p, 1, weekStart), -1, weekStart)).toEqual(p);
        // Consecutive periods touch: the next one starts the day after this one ends.
        expect(daysBetween(p.to!, shift(u, p, 1, weekStart).from!)).toBe(1);
      }),
    );
  });
});
