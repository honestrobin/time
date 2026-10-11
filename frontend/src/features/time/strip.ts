// SPDX-License-Identifier: AGPL-3.0-only
// The week as strips of paper: each day a strip, each entry a band where it ran.
import type { TimeEntry } from "./hooks";

const DAY = 24 * 60;
const FIRST = 8 * 60;
const LAST = 19 * 60;

/** One entry on a day's strip, in minutes since midnight. */
export interface Band {
  entry: TimeEntry;
  start: number;
  end: number;
  /** Typed in without a start time, so it's drawn after the day's timed work, not where it happened. */
  untimed: boolean;
  /** Which client's ink, in order of first appearance in the week. */
  client: number;
}

export interface StripDay {
  date: string;
  seconds: number;
  bands: Band[];
}

export interface Strips {
  /** The hours every strip shows, in minutes since midnight: 08:00 to 19:00, or wider. */
  from: number;
  to: number;
  days: StripDay[];
  clients: { id: string; name: string }[];
}

function minutesOf(time: string): number {
  const [h, m] = time.split(":").map(Number);
  return h * 60 + m;
}

/**
 * Lays a week of entries out as strips. The strips show 08:00 to 19:00, or more when work started
 * earlier or ended later. Time typed in without a start time follows the day's timed work, or
 * starts at 08:00 on a day without any: it never stretches the hours shown back to the night.
 * [seconds] gives an entry's length, so a running timer's band grows.
 */
export function layoutStrips(days: string[], entries: TimeEntry[], seconds: (e: TimeEntry) => number): Strips {
  const clients: { id: string; name: string }[] = [];
  const ink = (e: TimeEntry) => {
    const i = clients.findIndex((c) => c.id === e.client.id);
    if (i >= 0) return i;
    clients.push({ id: e.client.id, name: e.client.name });
    return clients.length - 1;
  };
  let from = FIRST;
  let to = LAST;
  const timed = days.map((date) =>
    entries
      .filter((e) => e.spent_date === date && e.start_time)
      .sort((a, b) => minutesOf(a.start_time!) - minutesOf(b.start_time!))
      .map((e): Band => {
        const start = minutesOf(e.start_time!);
        const end = Math.min(DAY, start + seconds(e) / 60);
        from = Math.min(from, Math.floor(start / 60) * 60);
        to = Math.max(to, Math.ceil(end / 60) * 60);
        return { entry: e, start, end, untimed: false, client: ink(e) };
      }),
  );
  const laid = days.map((date, i) => {
    const bands = timed[i];
    let at = bands.length ? Math.max(...bands.map((b) => b.end)) : FIRST;
    for (const e of entries.filter((x) => x.spent_date === date && !x.start_time)) {
      const end = Math.min(DAY, at + seconds(e) / 60);
      bands.push({ entry: e, start: at, end, untimed: true, client: ink(e) });
      to = Math.max(to, Math.ceil(end / 60) * 60);
      at = end;
    }
    return { date, seconds: entries.filter((e) => e.spent_date === date).reduce((s, e) => s + seconds(e), 0), bands };
  });
  return { from, to: Math.min(DAY, to), days: laid, clients };
}
