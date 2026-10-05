# 0023. When Paddle reports more than the locked price, you hear it from us

- Status: accepted
- Date: 2026-10-05
- Spec reference: §14, AT-6.2
- Supersedes: the last sentence of 0013 decision 2 ("we keep the lock and log an error for a person
  to look at")

## Context

A subscription's price per seat is locked on our record: the database refuses to raise it, and
seat changes are sent to Paddle on the subscription's own price (0013, decision 2). Paddle takes
the payment, though, so a price changed in Paddle's dashboard by mistake could still be charged.
Until now, when a webhook reported a higher price, we kept the lock and logged an error. The
customer wasn't told, and would first see it on Paddle's receipt.

## Decision

1. **The lock stays,** as before: a higher price from Paddle never changes our record.
2. **It's recorded** as a billing event of ours, once for each price in each billing period. The
   event's id carries the subscription, the end of the billing period and the price
   (`price_above_lock:<subscription>:<period end, epoch seconds>:<price, minor units>`), so it needs
   no new column.
3. **The billing page shows it:** "Paddle reported €90.00 per person a year, your locked price is
   €84.00 per person a year. We've been told, and we'll refund the difference."
4. **Every admin of the account gets an email** once, with both prices, that says nothing is
   needed from them.
5. **We hear of it** through an error in the app's log that starts with `PRICE LOCK:` and names
   the billing event. The mail setup has no operator address to email, so the log is the only
   channel until one is added.
6. **The refund is by hand, for now.** A person refunds the difference in Paddle, fixes the
   subscription's price there, and sets the billing event's type to `price_above_lock_refunded`.
   The billing page then stops showing it.

## Consequences

- The customer hears about it from us, before or with Paddle's receipt, and doesn't have to ask.
- "We've been told" on the billing page rests on the log line until there's an operator address.
  Adding one is a single setting and an extra recipient.
- Marking a refund is a manual database change. If this ever happens more than rarely, it gets a
  screen of its own.
