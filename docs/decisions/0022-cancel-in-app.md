# 0022. Cancel in the app, at the end of the period

- Status: accepted (built against a Paddle stand-in; VERIFY the two Paddle calls in its sandbox before launch)
- Date: 2026-10-05
- Spec reference: §14, principle 4 ("cancel in-app in two clicks")

## Context

The Robin's Code promises "Cancel in the app, in two clicks." Until now the billing page only
opened Paddle's customer portal, where cancelling took several screens on another site.

## Decision

1. **Two clicks on the billing page:** "Cancel the Team plan", then a dialog that says plainly what
   happens, and its button "Cancel at the end of this period". The dialog says: everything stays
   as it is until the end of the period that's paid for, with the date; then the free plan, for as
   many people as it holds, and if more can sign in, the account is read-only until the admin
   chooses who stays (decision record 0021) or starts Team again; the data and the export don't
   change. No question about leaving, no offer, no survey. The other button says "Close".
2. **At the end of the period, never at once.** `POST /api/v1/billing/cancellation` asks Paddle to
   cancel with `effective_from: next_billing_period`. Nothing is refunded and nothing more is
   charged for the period already paid; the plan just doesn't renew. Cancelling at once stays for
   deleting an account and for a second subscription made by mistake, as before.
3. **Asked twice, Paddle hears it once.** The end date is claimed on our record before Paddle is
   asked. If Paddle can't be reached, the claim is given back, nothing changes, and the admin is
   told so (`502 billing_provider_unavailable`).
4. **It can be taken back until that day.** "Keep the Team plan" (`DELETE
   /api/v1/billing/cancellation`) asks Paddle to drop the scheduled cancellation, and the plan
   renews as before, on the same day and at the same locked price. Nothing is charged at that
   moment.
5. **Paddle's webhooks stay the truth.** `subscription.updated` carries the scheduled cancellation
   (`scheduled_change`), and `subscription.canceled` ends the subscription on the day, as any
   other cancellation does.
6. **Admins only,** like everything else on the billing page.

## Consequences

- The customer portal stays for payment methods and Paddle's invoices; a cancellation made there
  still reaches us by webhook.
- VERIFY with Paddle's sandbox: that `effective_from: next_billing_period` schedules the
  cancellation and its answer carries `scheduled_change.effective_at`; that `PATCH
  /subscriptions/{id}` with `scheduled_change: null` removes it and charges nothing; and whether
  Paddle accepts seat changes on a subscription with a scheduled cancellation. If it doesn't, the
  seat sync keeps retrying until the plan ends, and a person who joins in that time isn't billed.
