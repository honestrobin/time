# 0013. Cloud billing with Paddle

- Status: accepted (built against a Paddle stand-in; VERIFY the API shapes in Paddle's sandbox before launch); decision 3 (seats) is superseded by 0020; decision 5 (when a subscription ends) in part by 0021; the last sentence of decision 2 by 0023
- Date: 2026-10-03
- Spec reference: §14, AT-6.2, AT-6.5

## Decisions

1. **Paddle Billing is the merchant of record.** The checkout runs in Paddle.js in the browser
   (`POST /api/v1/billing/checkout` returns what it needs); Paddle reports the subscription by
   webhook (`/webhooks/paddle`, `Paddle-Signature` HMAC, five-minute tolerance, each event once).
   Payment methods, tax and Paddle's invoices live in Paddle's customer portal
   (`POST /api/v1/billing/portal`). The billing module, its endpoints and its CSP additions exist
   in the cloud edition only (spec §1.2.3 allows exactly this module to differ).
2. **The price lock is two locks.** The database refuses to raise
   `subscriptions.locked_unit_price_minor` or change the currency (trigger
   `subscriptions_price_lock`, AT-6.2). And seat changes are sent to Paddle on the subscription's
   own price id, never the current list price, so Paddle keeps charging the old price. If Paddle
   ever reports a higher unit price, we keep the lock and log an error for a person to look at.
3. **Seats** are active people who can sign in. A job keeps Paddle's quantity in step every 15
   minutes, with proration. Deactivated people are free.
4. **Free plan: one person** (`honestrobin.billing.free-plan.seats`, default 1). The only plan
   limit is on seats: inviting a second person needs a Team subscription (`402
   subscription_required`). Adding people without inviting them is allowed, so an import from
   Harvest comes over completely before anyone pays.
5. **When a subscription ends** (cancelled or paused) and the account has more people than the
   free plan, the account turns read-only (`lapsed`); export keeps working (AT-6.3). A new or
   resumed subscription lifts it.
6. **List prices are configuration** (`honestrobin.billing.*`, default €7 per person per month
   yearly, €8.50 monthly) and only shown on the billing page; Paddle's prices are what is charged.

## Open questions

- The Paddle account, its price ids and the currencies to sell in (decision record 0009).
- What happens to the locked price when a subscription is cancelled and restarted later
  Today a restart is a new subscription at the then list price.
- The terms of service clauses (price lock, change of control) that the billing page should link to.
