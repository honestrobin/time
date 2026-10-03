// SPDX-License-Identifier: AGPL-3.0-only
// Calendar dates as ISO strings (YYYY-MM-DD), independent of the browser's time zone.

export function todayIn(timeZone: string): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone, year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date());
}

export function addDays(iso: string, days: number): string {
  const d = new Date(`${iso}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

/** ISO weekday 1 (Monday) … 7 (Sunday). */
export function isoWeekday(iso: string): number {
  const day = new Date(`${iso}T00:00:00Z`).getUTCDay();
  return day === 0 ? 7 : day;
}

export function startOfWeek(iso: string, weekStart: number): string {
  const diff = (isoWeekday(iso) - weekStart + 7) % 7;
  return addDays(iso, -diff);
}

export function weekDays(start: string): string[] {
  return Array.from({ length: 7 }, (_, i) => addDays(start, i));
}
