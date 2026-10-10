// SPDX-License-Identifier: AGPL-3.0-only
// The small sums behind Track, kept apart from the screen so they can be tested on their own.
import { formatDuration, formatTimeOfDay } from "../../lib/format";
import type { TimeEntry } from "./hooks";

/** The tab's title while a timer runs: "1:24 · Booking app · Time". */
export function runningTabTitle(seconds: number, project: string): string {
  return `${formatDuration(seconds)} · ${project} · Time`;
}

/**
 * How full each day's bar is, in percent of the week's longest day. It shows how the week fills
 * up, never a target: the longest day is full, an empty day is empty, and an empty week shows no bars.
 */
export function fillPercents(totals: number[]): number[] {
  const longest = Math.max(0, ...totals);
  return totals.map((s) => (longest > 0 ? Math.round((Math.max(0, s) / longest) * 100) : 0));
}

/**
 * When an entry ran: "08:40 – 10:05", or "13:05 – now" while it runs. Time added by hand has no
 * start, so it has no range.
 */
export function entryRange(entry: Pick<TimeEntry, "start_time" | "end_time" | "is_running">, now: string): string | null {
  if (!entry.start_time) return null;
  const start = formatTimeOfDay(entry.start_time);
  if (entry.is_running) return `${start} – ${now}`;
  return entry.end_time ? `${start} – ${formatTimeOfDay(entry.end_time)}` : null;
}
