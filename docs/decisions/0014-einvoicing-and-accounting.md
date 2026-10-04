# 0014. E-invoicing, Peppol sending and accounting sync (M5)

- Status: accepted (provider APIs VERIFY in sandboxes before launch)
- Date: 2026-10-03
- Spec reference: §7, §8, AT-5.1 to AT-5.3

## Decisions

1. **One source for every format.** Mustangproject builds the EN 16931 invoice from our data and
   writes the CII (Factur-X/ZUGFeRD hybrid PDF, XRechnung). The Peppol BIS UBL is converted from
   that same CII (en16931-cii2ubl), so the formats can't disagree. If the e-invoice total would
   differ from the invoice total by even a minor unit, no e-invoice is made.
2. **Checked by the official rules.** Tests validate XRechnung (KoSIT rules 3.0.2 and EN 16931),
   Peppol BIS (OpenPeppol 2026-05) and the Factur-X XML (EN 16931) with phive's rule sets, and
   the hybrid PDF with veraPDF as PDF/A-3b.
3. **Readiness in words.** Each format says what it still needs and where to add it (account,
   client, invoice). Peppol needs real participant IDs on both sides: the Peppol network
   doesn't route by email address.
4. **Hybrid PDFs by the account's own country.** On by default where the account's country is
   in the EU (where e-invoicing law is), a setting everywhere; with incomplete data the PDF
   stays plain. This follows decision record 0009: a rule about the account, not a market default.
5. **Peppol sending through a provider interface**, Storecove first: our own validated document
   goes as raw data, one transmission per attempt, idempotent by the transmission id, delivery
   from webhooks behind a secret header. Accounts use their own Storecove key, or the operator's
   contract where the instance offers one. Changed after the security review of 4 October 2026:
   the operator's contract is off unless switched on with
   `HONESTROBIN_STORECOVE_PLATFORM_SENDING_WITHOUT_ID_CHECK`, because nothing checks that a Peppol
   ID belongs to the account that registers it. Honest Robin Cloud keeps it off until such a check
   exists; the maintainer decided so that day.
6. **Accounting sync is one-way and queued.** Invoices are pushed when issued, payments when
   recorded, to QuickBooks Online and Xero, through `accounting_sync_items` with backoff (1, 5,
   30, 120, 720 minutes) and a visible failed state. Idempotent twice over: `external_links`
   remembers what was created, and the providers' idempotency keys (QuickBooks `requestid`, Xero
   `Idempotency-Key`) catch a lost answer. Missing choices (a tax code, the product or account
   to book on) fail at once with a sentence saying what to choose, and nothing is created at
   the provider until they're made.

## Not done yet

- Checking that a Peppol ID belongs to the account that registers it, before sending through
  the operator's contract (see 5).

- Credit notes, as e-invoices and in accounting.
- Customer matching by email with a manual override screen (spec §8); today customers are
  matched by name, then created.
- Two-way accounting sync (payments recorded in QuickBooks or Xero) — P1.
