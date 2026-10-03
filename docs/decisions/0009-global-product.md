# 0009. A global product, not a regional one

- Status: accepted
- Date: 2026-10-02
- Spec reference: §1.1, §1.2.6, §2.1, §3.5, §5.5, §14

## Context

The spec leans European: the one-liner sells "native EU e-invoicing", the cloud promises EU data residency, prices are in euros, and the first build defaulted new accounts to EUR and offered 24 currencies. On 2 October it was decided that the product must be global and must not be scoped to any one market, the owner's own country included.

EU e-invoicing stays a strength of the product. It is one country-specific capability among several to come, not the frame for everything else.

## Decision

1. **No country is the default.** Sign-up takes the time zone from the browser and guesses the currency and the first day of the week from the browser's region (USD and Monday when the region is unknown). The API accepts `week_start` at sign-up.
2. **Every ISO 4217 currency is offered**, with 0, 2 or 3 decimal places. The server already accepted any valid code; the picker now lists them all.
3. **Neutral wording.** The tax number is "Tax ID", not "VAT ID". Examples in the copy do not assume Europe.
4. **Numbers are read the way the user's locale writes them.** `parseMoney` handles decimal commas, thousands separators, spaces and apostrophes, Indian grouping, full-width and Arabic-Indic digits. Formatted numbers use Latin digits while the UI is English.
5. **Country-specific rules are modules behind a neutral core.** E-invoice formats and networks (EN 16931, Peppol, and later national platforms), tax models and payment details are added per country and prioritised by demand, not by where the maintainer lives. The neutral core is: two named taxes per invoice (§5.5), free-form payment instructions, and any currency per client.
6. **Tests cover the world.** Property tests run money through every available currency and 14 locales, and `WorldClockTest` runs accounts from UTC-11 to UTC+14, through daylight-saving changes in both hemispheres.

## Consequences

- M3 (invoicing) must not hard-wire IBAN/BIC as the only payment details, or VAT as the only tax model. Bank details outside SEPA, US sales tax and GST need to fit the same invoice.
- The time-zone and currency defaults in the V1 migration (`Europe/Berlin`, `EUR`) are never used, because the service always sets both. They are left alone to avoid a migration for no behaviour change.
- Translations: the UI is English only. `i18n` is in place (§3.5), and number, date and currency formatting already follow the browser's locale.

## Open questions

- **Data residency (§1.2.6).** "EU data residency" is a promise to European customers. Customers elsewhere may ask for a region closer to them. Decide whether the cloud stays EU-only or adds regions later.
- **Price list currencies (§14).** Prices are in euros. Paddle can sell in local currencies; decide which, and how the price lock is expressed per currency.
- **Which national e-invoicing platforms come first** after Peppol (§2.2 lists KSeF, Chorus Pro, SDI and Croatian fiscalization as backlog). Order them by where the customers are.
