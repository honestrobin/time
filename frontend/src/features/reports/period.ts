// SPDX-License-Identifier: AGPL-3.0-only
// Report periods: a unit (week, month, …) and the dates it covers, stepped back and forth.
import { addDays, startOfWeek } from "../../lib/dates";

export const UNITS = ["week", "month", "quarter", "year", "all", "custom"] as const;
export type Unit = (typeof UNITS)[number];

export interface Range {
  from?: string;
  to?: string;
}

const pad = (n: number) => String(n).padStart(2, "0");
const ymd = (y: number, m: number, d: number) => `${y}-${pad(m)}-${pad(d)}`;
const daysInMonth = (y: number, m: number) => new Date(Date.UTC(y, m, 0)).getUTCDate();

/** The first day of the month [months] away from [iso]'s month. */
function monthStart(iso: string, months = 0): { y: number; m: number } {
  const [y, m] = iso.split("-").map(Number);
  const index = y * 12 + (m - 1) + months;
  return { y: Math.floor(index / 12), m: (index % 12) + 1 };
}

/** The period of [unit] that contains [anchor]. */
export function periodFor(unit: Unit, anchor: string, weekStart: number): Range {
  switch (unit) {
    case "week": {
      const from = startOfWeek(anchor, weekStart);
      return { from, to: addDays(from, 6) };
    }
    case "month": {
      const { y, m } = monthStart(anchor);
      return { from: ymd(y, m, 1), to: ymd(y, m, daysInMonth(y, m)) };
    }
    case "quarter": {
      const { y, m } = monthStart(anchor);
      const first = Math.floor((m - 1) / 3) * 3 + 1;
      return { from: ymd(y, first, 1), to: ymd(y, first + 2, daysInMonth(y, first + 2)) };
    }
    case "year": {
      const y = Number(anchor.slice(0, 4));
      return { from: ymd(y, 1, 1), to: ymd(y, 12, 31) };
    }
    case "all":
      return {};
    case "custom":
      return { from: anchor, to: anchor };
  }
}

/** The previous (-1) or next (1) period. A custom range moves by its own length. */
export function shift(unit: Unit, range: Range, direction: 1 | -1, weekStart: number): Range {
  if (!range.from || !range.to || unit === "all") return range;
  switch (unit) {
    case "week":
      return periodFor("week", addDays(range.from, 7 * direction), weekStart);
    case "month": {
      const { y, m } = monthStart(range.from, direction);
      return periodFor("month", ymd(y, m, 1), weekStart);
    }
    case "quarter": {
      const { y, m } = monthStart(range.from, 3 * direction);
      return periodFor("quarter", ymd(y, m, 1), weekStart);
    }
    case "year":
      return periodFor("year", `${Number(range.from.slice(0, 4)) + direction}-01-01`, weekStart);
    case "custom": {
      const days = daysBetween(range.from, range.to) + 1;
      return { from: addDays(range.from, days * direction), to: addDays(range.to, days * direction) };
    }
  }
}

export function daysBetween(from: string, to: string): number {
  return Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000);
}

/** Whether [range] is the period of [unit] that contains [today]. */
export function isCurrent(unit: Unit, range: Range, today: string, weekStart: number): boolean {
  if (unit === "all" || unit === "custom") return false;
  const now = periodFor(unit, today, weekStart);
  return now.from === range.from && now.to === range.to;
}
