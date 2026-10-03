// SPDX-License-Identifier: AGPL-3.0-only
import fc from "fast-check";
import { afterEach, describe, expect, it } from "vitest";
import { currencyDigits, formatDuration, formatMoney, minorToInput, parseDuration, parseMoney, roundSeconds, setFormatLocale } from "./format";

// Places that write numbers differently: decimal comma, spaces or apostrophes for thousands,
// Indian lakh grouping, and locales whose own digits are not 0-9.
const LOCALES = ["en-US", "en-GB", "de-DE", "fr-FR", "de-CH", "it-IT", "sv-SE", "pt-BR", "en-IN", "ja-JP", "ko-KR", "ar-EG", "hi-IN", "fa-IR"];
// 0, 2 and 3 decimal places.
const CURRENCIES = ["USD", "EUR", "GBP", "JPY", "KRW", "INR", "BRL", "CHF", "KWD", "BHD", "CLP", "NZD"];

const locale = fc.constantFrom(...LOCALES);
const currency = fc.constantFrom(...CURRENCIES);
const minor = fc.integer({ min: 0, max: 2_000_000_000_00 });

// FC_RUNS=20000 npx vitest run src/lib/format.property.test.ts for a deeper search.
fc.configureGlobal({ numRuns: Number(process.env.FC_RUNS ?? 200) });

afterEach(() => setFormatLocale("en-US"));

/** The same integer arithmetic the server uses (Money.roundSeconds). */
function serverRound(seconds: number, minutes: number, mode: "up" | "nearest"): number {
  if (minutes <= 0 || seconds === 0) return seconds;
  const step = minutes * 60;
  return mode === "nearest" ? Math.floor((seconds + Math.floor(step / 2)) / step) * step : Math.floor((seconds + step - 1) / step) * step;
}

describe("roundSeconds", () => {
  const seconds = fc.integer({ min: 0, max: 86_400 });
  const step = fc.constantFrom(0, 6, 15, 30);
  const mode = fc.constantFrom("up" as const, "nearest" as const);

  it("gives the same result as the server for every duration, step and mode", () => {
    fc.assert(fc.property(seconds, step, mode, (s, m, md) => roundSeconds(s, m, md) === serverRound(s, m, md)));
  });

  it("lands on a step, never moves time by a full step, and is stable when applied twice", () => {
    fc.assert(
      fc.property(seconds, fc.constantFrom(6, 15, 30), mode, (s, m, md) => {
        const r = roundSeconds(s, m, md);
        const stepSeconds = m * 60;
        expect(r % stepSeconds).toBe(0);
        expect(Math.abs(r - s)).toBeLessThan(stepSeconds);
        if (md === "up") expect(r).toBeGreaterThanOrEqual(s);
        else expect(Math.abs(r - s)).toBeLessThanOrEqual(stepSeconds / 2);
        expect(roundSeconds(r, m, md)).toBe(r);
      }),
    );
  });

  it("never reorders two durations", () => {
    fc.assert(fc.property(seconds, seconds, step, mode, (a, b, m, md) => (a <= b ? roundSeconds(a, m, md) <= roundSeconds(b, m, md) : true)));
  });
});

describe("durations", () => {
  it("what the app shows as hours and minutes parses back to the same whole minutes", () => {
    fc.assert(fc.property(fc.integer({ min: 0, max: 3 * 86_400 }), (s) => parseDuration(formatDuration(s, "hm")) === Math.floor(s / 60) * 60));
  });

  it("decimal hours parse back to within the two decimals shown, in every locale", () => {
    fc.assert(
      fc.property(locale, fc.integer({ min: 0, max: 3 * 86_400 }), (l, s) => {
        setFormatLocale(l);
        const parsed = parseDuration(formatDuration(s, "decimal"));
        expect(parsed).not.toBeNull();
        expect(Math.abs(parsed! - s)).toBeLessThanOrEqual(18);
      }),
    );
  });

  it("never returns a negative, fractional or non-finite duration, whatever is typed", () => {
    fc.assert(
      fc.property(fc.string(), (text) => {
        const parsed = parseDuration(text);
        if (parsed !== null) expect(Number.isInteger(parsed) && parsed >= 0).toBe(true);
      }),
    );
  });
});

describe("money", () => {
  it("the value in an input field parses back to the same amount", () => {
    fc.assert(
      fc.property(locale, currency, minor, (l, c, m) => {
        setFormatLocale(l);
        expect(parseMoney(minorToInput(m, c), c)).toBe(m);
      }),
    );
  });

  it("an amount as the app displays it parses back to the same amount, in every locale", () => {
    fc.assert(
      fc.property(locale, currency, minor, (l, c, m) => {
        setFormatLocale(l);
        expect(parseMoney(formatMoney(m, c), c)).toBe(m);
      }),
    );
  });

  it("a number typed the way the locale writes it, thousands separators included, keeps its value", () => {
    fc.assert(
      fc.property(locale, currency, minor, fc.boolean(), (l, c, m, withDecimals) => {
        setFormatLocale(l);
        const digits = currencyDigits(c);
        const units = withDecimals ? m : Math.floor(m / 10 ** digits) * 10 ** digits;
        const typed = new Intl.NumberFormat(l, {
          numberingSystem: "latn",
          useGrouping: true,
          minimumFractionDigits: withDecimals ? digits : 0,
          maximumFractionDigits: withDecimals ? digits : 0,
        }).format(units / 10 ** digits);
        expect(parseMoney(typed, c)).toBe(units);
      }),
    );
  });

  it("reads a lone separator before three digits the way the locale writes numbers", () => {
    setFormatLocale("en-US");
    expect(parseMoney("1,000", "USD")).toBe(1000_00);
    expect(parseMoney("1.000", "USD")).toBe(1_00);
    expect(parseMoney("1,000", "JPY")).toBe(1000);
    setFormatLocale("de-DE");
    expect(parseMoney("1.000", "EUR")).toBe(1000_00);
    expect(parseMoney("1,000", "EUR")).toBe(1_00);
    expect(parseMoney("1.234.567,89", "EUR")).toBe(1_234_567_89);
    setFormatLocale("fr-FR");
    expect(parseMoney("1 000,50", "EUR")).toBe(1000_50);
    setFormatLocale("de-CH");
    expect(parseMoney("1’000.50", "CHF")).toBe(1000_50);
  });

  it("reads digits typed with a Japanese or Arabic keyboard", () => {
    setFormatLocale("ja-JP");
    expect(parseMoney("１，２００", "JPY")).toBe(1200);
    setFormatLocale("ar-EG");
    expect(parseMoney("١٢٫٥٠", "USD")).toBe(12_50);
    expect(parseDuration("١:٣٠")).toBe(5400);
  });

  it("rounds extra decimals half up without floating-point drift", () => {
    setFormatLocale("en-US");
    expect(parseMoney("1.005", "USD")).toBe(1_01);
    expect(parseMoney("1.15", "USD")).toBe(1_15);
    expect(parseMoney("0.29", "USD")).toBe(29);
    expect(parseMoney("1.4", "JPY")).toBe(1);
    expect(parseMoney("1.5", "JPY")).toBe(2);
  });

  it("returns null when there is no number, and a safe integer otherwise", () => {
    expect(parseMoney("", "USD")).toBeNull();
    expect(parseMoney("abc", "USD")).toBeNull();
    expect(parseMoney("-12.50", "USD")).toBe(-12_50);
    fc.assert(
      fc.property(fc.string(), currency, (text, c) => {
        const parsed = parseMoney(text, c);
        if (parsed !== null) expect(Number.isSafeInteger(parsed)).toBe(true);
      }),
    );
  });
});
