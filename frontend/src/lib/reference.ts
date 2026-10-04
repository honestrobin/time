// SPDX-License-Identifier: AGPL-3.0-only
export const TIME_ZONES: string[] =
  typeof Intl.supportedValuesOf === "function" ? Intl.supportedValuesOf("timeZone") : ["UTC", "Europe/Berlin", "Europe/London", "America/New_York"];

const COMMON_CURRENCIES = [
  "EUR", "USD", "GBP", "CHF", "SEK", "NOK", "DKK", "PLN", "CZK", "HUF", "RON", "CAD", "AUD", "NZD", "JPY", "INR", "BRL", "MXN", "ZAR", "SGD", "HKD", "ILS", "TRY",
];

/** Every ISO 4217 currency the browser knows; the server accepts any valid code. */
export const CURRENCIES: string[] = typeof Intl.supportedValuesOf === "function" ? Intl.supportedValuesOf("currency") : COMMON_CURRENCIES;

const EURO_REGIONS = "AT BE BG CY DE EE ES FI FR GR HR IE IT LT LU LV MT NL PT SI SK";
const REGION_CURRENCY: Record<string, string> = {
  ...Object.fromEntries(EURO_REGIONS.split(" ").map((r) => [r, "EUR"])),
  US: "USD", GB: "GBP", CH: "CHF", LI: "CHF", SE: "SEK", NO: "NOK", DK: "DKK", IS: "ISK", PL: "PLN", CZ: "CZK", HU: "HUF", RO: "RON",
  RS: "RSD", UA: "UAH", TR: "TRY", IL: "ILS", AE: "AED", SA: "SAR", EG: "EGP", ZA: "ZAR", NG: "NGN", KE: "KES", CA: "CAD", MX: "MXN",
  BR: "BRL", AR: "ARS", CL: "CLP", CO: "COP", PE: "PEN", AU: "AUD", NZ: "NZD", JP: "JPY", CN: "CNY", HK: "HKD", TW: "TWD", KR: "KRW",
  SG: "SGD", IN: "INR", PK: "PKR", BD: "BDT", ID: "IDR", MY: "MYR", TH: "THB", PH: "PHP", VN: "VND",
};

function region(locale: string): string | undefined {
  try {
    return new Intl.Locale(locale).maximize().region;
  } catch {
    return undefined;
  }
}

/** A first guess at the currency someone works in, from their browser locale. They can change it. */
export function guessCurrency(locale: string): string {
  return REGION_CURRENCY[region(locale) ?? ""] ?? "USD";
}

/**
 * The account's locale, which decides how dates look on invoices, their PDFs and the client's
 * page. The words are English for now, so the date style follows the browser's region (en-GB,
 * en-US, en-AU…), and a browser in another language gets international English ("4 Oct 2026").
 */
export function invoiceLocale(browserLocale: string): string {
  try {
    const l = new Intl.Locale(browserLocale);
    return l.language === "en" ? l.toString() : "en-001";
  } catch {
    return "en-001";
  }
}

/** The date styles offered in account settings, each shown as an example date, without repeats. */
export function dateStyleOptions(current: string): { value: string; label: string }[] {
  const sample = new Date(2026, 9, 4, 12);
  const seen = new Set<string>();
  const options: { value: string; label: string }[] = [];
  for (const value of [current, "en-GB", "en-US"]) {
    let label: string;
    try {
      label = new Intl.DateTimeFormat(value, { dateStyle: "medium" }).format(sample);
    } catch {
      continue;
    }
    if (seen.has(label)) continue;
    seen.add(label);
    options.push({ value, label });
  }
  return options;
}

/** The first day of the week where the locale is used: 1 (Monday), 6 (Saturday) or 7 (Sunday). */
export function guessWeekStart(locale: string): number {
  try {
    const l = new Intl.Locale(locale).maximize() as Intl.Locale & { getWeekInfo?: () => { firstDay: number }; weekInfo?: { firstDay: number } };
    const first = l.getWeekInfo?.().firstDay ?? l.weekInfo?.firstDay;
    return first === 6 || first === 7 ? first : 1;
  } catch {
    return 1;
  }
}
