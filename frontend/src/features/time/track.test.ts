// SPDX-License-Identifier: AGPL-3.0-only
import { describe, expect, it } from "vitest";
import { setFormatLocale } from "../../lib/format";
import { entryRange, fillPercents, runningTabTitle } from "./track";

describe("the week filling up", () => {
  it("measures each day against the week's longest, never against a target", () => {
    expect(fillPercents([3600, 7200, 0, 1800, 0, 0, 0])).toEqual([50, 100, 0, 25, 0, 0, 0]);
    // One short day is a full bar: there's no goal it falls short of.
    expect(fillPercents([0, 0, 600, 0, 0, 0, 0])).toEqual([0, 0, 100, 0, 0, 0, 0]);
  });

  it("shows no bars for an empty week", () => {
    expect(fillPercents([0, 0, 0, 0, 0, 0, 0])).toEqual([0, 0, 0, 0, 0, 0, 0]);
  });
});

describe("an entry's range", () => {
  it("is from start to end, or to now while it runs", () => {
    setFormatLocale("en-GB");
    expect(entryRange({ start_time: "08:40:00", end_time: "10:05:00", is_running: false }, "now")).toBe("08:40 – 10:05");
    expect(entryRange({ start_time: "13:05:00", is_running: true }, "now")).toBe("13:05 – now");
  });

  it("is written the way the reader writes the time", () => {
    setFormatLocale("en-US");
    expect(entryRange({ start_time: "08:40:00", end_time: "13:05:00", is_running: false }, "now")).toBe("8:40 AM – 1:05 PM");
    setFormatLocale("en-GB");
  });

  it("is left out for time added by hand", () => {
    expect(entryRange({ is_running: false }, "now")).toBeNull();
  });
});

describe("the tab while a timer runs", () => {
  it("shows the running time and the project", () => {
    expect(runningTabTitle(5047, "Booking app")).toBe("1:24 · Booking app · Time");
  });
});
