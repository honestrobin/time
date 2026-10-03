// SPDX-License-Identifier: AGPL-3.0-only
import { describe, expect, it } from "vitest";
import { formatDuration, parseDuration, parseMoney, roundSeconds, setFormatLocale } from "./format";
import { addDays, isoWeekday, startOfWeek } from "./dates";

setFormatLocale("en-US");

describe("parseDuration", () => {
  it.each([
    ["1:30", 5400],
    [":45", 2700],
    ["1.5", 5400],
    ["1,5", 5400],
    ["2", 7200],
    ["90m", 5400],
    ["1h30m", 5400],
    ["1h 30m", 5400],
    ["2h", 7200],
    ["", 0],
  ])("%s → %i seconds", (input, seconds) => {
    expect(parseDuration(input)).toBe(seconds);
  });

  it("rejects nonsense", () => {
    expect(parseDuration("abc")).toBeNull();
    expect(parseDuration("1:75")).toBeNull();
  });
});

describe("formatDuration", () => {
  it("formats hours and minutes", () => {
    expect(formatDuration(5400)).toBe("1:30");
    expect(formatDuration(59)).toBe("0:00");
    expect(formatDuration(36_000 + 60)).toBe("10:01");
  });

  it("formats decimal hours", () => {
    expect(formatDuration(5400, "decimal")).toBe("1.50");
  });
});

describe("roundSeconds", () => {
  it("rounds up to the account step like a time card", () => {
    expect(roundSeconds(61, 15)).toBe(900);
    expect(roundSeconds(900, 15)).toBe(900);
    expect(roundSeconds(61, 0)).toBe(61);
  });

  it("rounds to the nearest step, with the half going up, like the server", () => {
    expect(roundSeconds(67 * 60, 15, "nearest")).toBe(60 * 60);
    expect(roundSeconds(68 * 60, 15, "nearest")).toBe(75 * 60);
    expect(roundSeconds(67 * 60 + 30, 15, "nearest")).toBe(75 * 60);
    expect(roundSeconds(61, 0, "nearest")).toBe(61);
  });
});

describe("parseMoney", () => {
  it("accepts both decimal separators", () => {
    expect(parseMoney("12.50", "EUR")).toBe(1250);
    expect(parseMoney("12,50", "EUR")).toBe(1250);
    expect(parseMoney("1.234,56", "EUR")).toBe(123456);
    expect(parseMoney("1,234.56", "USD")).toBe(123456);
    expect(parseMoney("1500", "JPY")).toBe(1500);
    expect(parseMoney("", "EUR")).toBeNull();
  });
});

describe("dates", () => {
  it("finds the week start for any start day", () => {
    expect(startOfWeek("2026-09-30", 1)).toBe("2026-09-28");
    expect(startOfWeek("2026-09-30", 7)).toBe("2026-09-27");
    expect(startOfWeek("2026-09-28", 1)).toBe("2026-09-28");
    expect(isoWeekday("2026-10-04")).toBe(7);
    expect(addDays("2026-12-31", 1)).toBe("2027-01-01");
  });
});
