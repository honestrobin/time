// SPDX-License-Identifier: AGPL-3.0-only
import { describe, expect, it } from "vitest";
import { CURRENCIES, guessCurrency, guessWeekStart } from "./reference";

describe("guessCurrency", () => {
  it("follows the region of the locale, wherever that is", () => {
    expect(guessCurrency("en-US")).toBe("USD");
    expect(guessCurrency("en-GB")).toBe("GBP");
    expect(guessCurrency("de-DE")).toBe("EUR");
    expect(guessCurrency("pt-BR")).toBe("BRL");
    expect(guessCurrency("ja-JP")).toBe("JPY");
    expect(guessCurrency("en-IN")).toBe("INR");
    expect(guessCurrency("en-AU")).toBe("AUD");
  });

  it("works from a bare language, and falls back to USD for anything unknown", () => {
    expect(guessCurrency("ja")).toBe("JPY");
    expect(guessCurrency("fr")).toBe("EUR");
    expect(guessCurrency("en-AQ")).toBe("USD");
    expect(guessCurrency("not a locale")).toBe("USD");
  });

  it("only ever suggests a currency the picker offers", () => {
    for (const locale of ["en-US", "sv-SE", "ko-KR", "es-AR", "ar-EG", "vi-VN", "xx"]) {
      expect(CURRENCIES).toContain(guessCurrency(locale));
    }
  });
});

describe("guessWeekStart", () => {
  it("is Sunday in the US, Monday in Germany, and always one of the offered days", () => {
    expect(guessWeekStart("en-US")).toBe(7);
    expect(guessWeekStart("de-DE")).toBe(1);
    for (const locale of ["en-GB", "ar-EG", "ja-JP", "pt-BR", "not a locale"]) {
      expect([1, 6, 7]).toContain(guessWeekStart(locale));
    }
  });
});
