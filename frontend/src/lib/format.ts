// SPDX-License-Identifier: AGPL-3.0-only
// Locale-aware formatting. Durations are integer seconds; money is integer minor units.

export type DurationStyle = "hm" | "decimal";

/**
 * The UI is in English, so numbers always use Latin digits: a browser set to Arabic or Hindi would
 * otherwise format with its own digits in the middle of English text.
 */
function withLatinDigits(l: string): string {
  try {
    return new Intl.Locale(l, { numberingSystem: "latn" }).toString();
  } catch {
    return "en";
  }
}

let locale = withLatinDigits(navigator.language || "en");

export function setFormatLocale(l: string) {
  locale = withLatinDigits(l);
  separatorCache = null;
}

/** Turns full-width (Japanese IME) and Arabic-Indic digits and punctuation into their ASCII forms. */
function asciiDigits(input: string): string {
  return input
    .normalize("NFKC")
    .replace(/[\u0660-\u0669]/g, (d) => String(d.charCodeAt(0) - 0x660))
    .replace(/[\u06f0-\u06f9]/g, (d) => String(d.charCodeAt(0) - 0x6f0))
    .replace(/\u066b/g, ".")
    .replace(/\u066c/g, ",");
}

let separatorCache: { decimal: string; group: string } | null = null;

/** The decimal and thousands separators of the current locale. */
function separators(): { decimal: string; group: string } {
  if (!separatorCache) {
    const parts = new Intl.NumberFormat(locale, { useGrouping: true }).formatToParts(1234567.5);
    separatorCache = {
      decimal: parts.find((x) => x.type === "decimal")?.value ?? ".",
      group: parts.find((x) => x.type === "group")?.value ?? ",",
    };
  }
  return separatorCache;
}

/** Applies the account's display rounding (0, 6, 15 or 30 minutes): up like a time card, or to the nearest step. */
export function roundSeconds(seconds: number, roundingMinutes: number, mode: "up" | "nearest" = "up"): number {
  if (!roundingMinutes) return seconds;
  const step = roundingMinutes * 60;
  return mode === "nearest" ? Math.floor((seconds + step / 2) / step) * step : Math.ceil(seconds / step) * step;
}

export function formatDuration(seconds: number, style: DurationStyle = "hm"): string {
  const negative = seconds < 0;
  const s = Math.abs(seconds);
  if (style === "decimal") {
    return (negative ? "-" : "") + new Intl.NumberFormat(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(s / 3600);
  }
  const totalMinutes = Math.floor(s / 60);
  const h = Math.floor(totalMinutes / 60);
  const m = totalMinutes % 60;
  return `${negative ? "-" : ""}${h}:${m.toString().padStart(2, "0")}`;
}

/** Running clock with seconds, e.g. 1:04:09. */
export function formatClock(seconds: number): string {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  return `${h}:${m.toString().padStart(2, "0")}:${s.toString().padStart(2, "0")}`;
}

/**
 * Parses what people type into a time field: "1:30", "1.5", "1,5", "90m", "1h 30m", "2h", ":45".
 * Returns seconds, or null if it cannot be understood.
 */
export function parseDuration(input: string): number | null {
  const v = asciiDigits(input).trim().toLowerCase().replace(/\s+/g, "");
  if (!v) return 0;
  let m = /^(\d*):(\d{1,2})$/.exec(v);
  if (m) {
    const minutes = Number(m[2]);
    if (minutes >= 60) return null;
    return (Number(m[1] || 0) * 60 + minutes) * 60;
  }
  m = /^(?:(\d+(?:[.,]\d+)?)h)?(?:(\d+)m(?:in)?)?$/.exec(v);
  if (m && (m[1] || m[2])) {
    return Math.round(Number((m[1] ?? "0").replace(",", ".")) * 3600) + Number(m[2] ?? 0) * 60;
  }
  m = /^(\d*[.,]?\d+)$/.exec(v);
  if (m) return Math.round(Number(m[1].replace(",", ".")) * 3600);
  return null;
}

const fractionCache = new Map<string, number>();

export function currencyDigits(currency: string): number {
  let d = fractionCache.get(currency);
  if (d === undefined) {
    d = new Intl.NumberFormat("en", { style: "currency", currency }).resolvedOptions().maximumFractionDigits ?? 2;
    fractionCache.set(currency, d);
  }
  return d;
}

export function formatMoney(minor: number | null | undefined, currency: string): string {
  if (minor === null || minor === undefined) return "";
  const digits = currencyDigits(currency);
  return new Intl.NumberFormat(locale, { style: "currency", currency, minimumFractionDigits: digits, maximumFractionDigits: digits }).format(
    minor / 10 ** digits,
  );
}

/**
 * Parses what people type or paste into a money field, in any locale: "12.50", "12,50", "1,000",
 * "1.000,50", "1 000,50", "$1,234.50". Returns minor units, or null if there is no number in it.
 *
 * A single separator followed by exactly three digits is ambiguous ("1,000" is a thousand in the US
 * and one in Germany); it is read the way the current locale writes numbers.
 */
export function parseMoney(input: string, currency: string): number | null {
  const text = asciiDigits(input).trim();
  const firstDigit = text.search(/\d/);
  if (firstDigit < 0) return null;
  const negative = /[-\u2212]/.test(text.slice(0, firstDigit));
  // Only what lies between the first and last digit is the number: a currency symbol can itself
  // contain dots ("Rs.", "kr.", "د.ب."). Inside it, spaces and apostrophes are just formatting.
  const lastDigit = text.search(/\d\D*$/);
  const v = text.slice(firstDigit, lastDigit + 1).replace(/[^\d.,]/g, "");

  const lastDot = v.lastIndexOf(".");
  const lastComma = v.lastIndexOf(",");
  let decimalAt = -1;
  if (lastDot >= 0 && lastComma >= 0) {
    decimalAt = Math.max(lastDot, lastComma);
  } else if (lastDot >= 0 || lastComma >= 0) {
    const at = Math.max(lastDot, lastComma);
    const sep = v[at];
    const once = v.indexOf(sep) === at;
    const threeAfter = v.length - at - 1 === 3;
    // "0.500" is never a thousand: nobody groups digits after a leading zero.
    const leadingZero = v.startsWith("0");
    const isGroup = !once || (threeAfter && at > 0 && !leadingZero && sep === separators().group);
    if (!isGroup) decimalAt = at;
  }
  const whole = (decimalAt >= 0 ? v.slice(0, decimalAt) : v).replace(/[.,]/g, "");
  const fraction = decimalAt >= 0 ? v.slice(decimalAt + 1) : "";

  // Scale with digits, not floats: 1.15 * 100 is 114.99999999999999 in floating point.
  const digits = currencyDigits(currency);
  let minor = Number((whole + fraction.padEnd(digits, "0").slice(0, digits)) || "0");
  if (fraction.length > digits && fraction[digits] >= "5") minor += 1;
  if (!Number.isSafeInteger(minor)) return null;
  return negative && minor !== 0 ? -minor : minor;
}

/** The plain number for an input field, with the locale's decimal separator and no thousands separators. */
export function minorToInput(minor: number | null | undefined, currency: string): string {
  if (minor === null || minor === undefined) return "";
  return (minor / 10 ** currencyDigits(currency)).toFixed(currencyDigits(currency)).replace(".", separators().decimal);
}

export function formatDate(iso: string, style: "short" | "medium" | "long" = "medium"): string {
  const d = iso.length === 10 ? new Date(`${iso}T12:00:00`) : new Date(iso);
  return new Intl.DateTimeFormat(locale, { dateStyle: style }).format(d);
}

/** "2026-03" → "Mar 2026" in the current locale. */
export function formatMonth(yearMonth: string): string {
  return new Intl.DateTimeFormat(locale, { month: "short", year: "numeric" }).format(new Date(`${yearMonth}-01T12:00:00`));
}

export function formatDateTime(iso: string): string {
  return new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeStyle: "short" }).format(new Date(iso));
}

export function formatWeekday(isoDate: string, style: "short" | "long" = "short"): string {
  return new Intl.DateTimeFormat(locale, { weekday: style }).format(new Date(`${isoDate}T12:00:00`));
}

export function formatPercent(fraction: number): string {
  return new Intl.NumberFormat(locale, { style: "percent", maximumFractionDigits: 0 }).format(fraction);
}
