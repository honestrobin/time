// SPDX-License-Identifier: AGPL-3.0-only
import { describe, expect, it } from "vitest";
import type { TimeEntry } from "./hooks";
import { layoutStrips } from "./strip";

const days = ["2026-10-05", "2026-10-06", "2026-10-07", "2026-10-08", "2026-10-09", "2026-10-10", "2026-10-11"];
let next = 0;
function entry(date: string, client: string, minutes: number, start?: string): TimeEntry {
  next += 1;
  return {
    id: `e${next}`,
    spent_date: date,
    start_time: start ? `${start}:00` : undefined,
    duration_seconds: minutes * 60,
    is_running: false,
    client: { id: client, name: client },
  } as TimeEntry;
}
const length = (e: TimeEntry) => e.duration_seconds;

describe("the week as strips", () => {
  it("puts each entry where it ran, on 08:00 to 19:00", () => {
    const s = layoutStrips(days, [entry(days[0], "Fernblick", 150, "08:55"), entry(days[0], "Kestrel", 105, "11:40")], length);
    expect([s.from, s.to]).toEqual([8 * 60, 19 * 60]);
    expect(s.days[0].bands.map((b) => [b.start, b.end])).toEqual([
      [8 * 60 + 55, 11 * 60 + 25],
      [11 * 60 + 40, 13 * 60 + 25],
    ]);
    expect(s.days[0].seconds).toBe(255 * 60);
    expect(s.days[1].bands).toEqual([]);
  });

  it("widens every strip, to whole hours, when work started earlier or ended later", () => {
    const s = layoutStrips(days, [entry(days[1], "A", 60, "06:30"), entry(days[3], "A", 90, "19:00")], length);
    expect([s.from, s.to]).toEqual([6 * 60, 21 * 60]);
  });

  it("puts time typed in without a start after the day's timed work", () => {
    const s = layoutStrips(days, [entry(days[2], "A", 30), entry(days[2], "B", 120, "09:00"), entry(days[2], "A", 15)], length);
    const bands = s.days[2].bands;
    expect(bands.map((b) => [b.start, b.end, b.untimed])).toEqual([
      [9 * 60, 11 * 60, false],
      [11 * 60, 11 * 60 + 30, true],
      [11 * 60 + 30, 11 * 60 + 45, true],
    ]);
  });

  it("starts a day with only typed-in time at 08:00, even when another day started earlier", () => {
    const s = layoutStrips(days, [entry(days[4], "A", 60), entry(days[6], "A", 30, "01:05")], length);
    expect(s.days[4].bands.map((b) => [b.start, b.end])).toEqual([[8 * 60, 9 * 60]]);
    expect(s.from).toBe(60);
  });

  it("never runs past midnight", () => {
    const s = layoutStrips(days, [entry(days[0], "A", 180, "22:30")], length);
    expect(s.days[0].bands[0].end).toBe(24 * 60);
    expect(s.to).toBe(24 * 60);
  });

  it("grows a running timer's band with its live length", () => {
    const running = { ...entry(days[4], "A", 10, "13:05"), is_running: true };
    const s = layoutStrips(days, [running], () => 85 * 60);
    expect(s.days[4].bands[0].end).toBe(13 * 60 + 5 + 85);
    expect(s.days[4].seconds).toBe(85 * 60);
  });

  it("gives each client one ink, in the order they first appear", () => {
    const s = layoutStrips(days, [entry(days[0], "B", 30, "09:00"), entry(days[0], "A", 30, "10:00"), entry(days[1], "B", 30, "09:00")], length);
    expect(s.clients.map((c) => c.name)).toEqual(["B", "A"]);
    expect(s.days.flatMap((d) => d.bands.map((b) => b.client))).toEqual([0, 1, 0]);
  });
});
