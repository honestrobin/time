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
   change; the locked price ends with the plan, and Team started again later is at the list price
   of that day (0013's open question). No question about leaving, no offer, no survey. The other
   button says "Close".
2. **At the end of the period, not at once.** `POST /api/v1/billing/cancellation` asks Paddle to
   cancel with `effective_from: next_billing_period`. Nothing is refunded and nothing more is
   charged for the period already paid; the plan just doesn't renew. **One exception: while a
   payment is past due,** that period isn't paid for, and Paddle takes no scheduled change, so the
   plan ends at once. Whether it's past due is asked of Paddle under the lock, not read from our
   record, which may not have heard of a payment yet. The dialog says so, and its button says
   "Cancel now". The request carries which one the admin saw (`ends`: `period_end` or `now`); if
   that's no longer true, nothing changes and the answer says which it is
   (`409 cancellation_changed`). Otherwise cancelling at once stays for deleting an account and for
   a second subscription made by mistake, as before.
3. **Paddle first, and asked once.** The change runs in a transaction that holds the
   subscription's lock (the one webhooks take) while Paddle answers, and our record changes only
   once Paddle said yes. A second click waits, then finds it done. If we stop between Paddle's yes
   and our commit, Paddle's webhook for the change brings our record in line. If Paddle doesn't
   answer, the admin sees `502 billing_provider_unavailable`, which says we don't know yet whether
   Paddle took it; its webhook will tell. If Paddle refuses (it takes no changes in the 30 minutes
   before a renewal), `409 billing_provider_refused`, with that reason, and nothing changed.
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
- In the 30 minutes before a renewal, or before the end date, Paddle takes no changes: cancelling
  or keeping is refused then, and the admin is told why. A renewal charged after a refused
  cancellation needs a person; that's a gap until Paddle's rule is checked.
- VERIFY with Paddle's sandbox: that `effective_from: next_billing_period` schedules the
  cancellation and its answer carries `scheduled_change.effective_at`; that `PATCH
  /subscriptions/{id}` with `scheduled_change: null` removes it and charges nothing; that a
  past-due subscription can be cancelled at once and Paddle stops collecting (if it doesn't, the
  customer could pay for a period they no longer have); that `GET /subscriptions/{id}` answers
  with `data.status`; whether a failed prorated seat charge can make a subscription past due in
  the middle of a paid period (then "ends now" would cut off a period partly paid for); the
  30-minute rule; and whether Paddle accepts seat changes on a subscription with a scheduled cancellation. If it
  doesn't, the seat sync keeps retrying until the plan ends, and a person who joins in that time
  isn't billed.
