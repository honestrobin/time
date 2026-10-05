# 0020. Seats: billed from the moment someone can sign in, and asked for first

- Status: accepted
- Date: 2026-10-05
- Spec reference: §14, AT-6.2
- Supersedes: decision 3 of 0013 (seats)

## Context

Decision record 0013 counted seats as the people who can sign in, and a job kept Paddle's count in
step every 15 minutes. Two places broke the Robin's Code, which promises "You see the exact amount
before you are charged" and that a limit is kept at the moment you act, with the price shown
first:

- Checkout counted open invitations, so a team paid for people who hadn't joined yet. The billing
  page counted only the people who can sign in, so the two numbers disagreed.
- On the Team plan, an invitation or bringing someone back added a paid seat without asking and
  without showing a price. On the free plan, an invitation beyond it was refused without a price
  or a way to move up.

## Decision

1. **A seat is a person who can sign in, and nothing else.** An invitation is never billed: not
   at checkout, not in the seat sync, not anywhere. Checkout, the seat sync and the billing page
   all use the same count (`SeatCounter.used`). The billing page shows open invitations on their
   own, as not billed. The free plan's limit still counts invitations
   (`SeatCounter.claimed`), because accepting one would take the person past it.
2. **Billing starts when someone can sign in.** When an invitation is accepted, or a person with
   sign-in access is brought back, Paddle is told at once, prorated from that moment
   (`SeatTaken`, handled by `BillingService.onSeatTaken`). If Paddle can't be reached, the job
   tries again within 15 minutes; the person can sign in either way. The job also lowers the
   count when people are deactivated.
3. **Asked first, at the moment you act.** On the Team plan, every request that gives someone
   sign-in access (an invitation, an invitation link, bringing someone back) is refused with
   `409 seat_confirmation_required` and the price per person, until an admin sends it again with
   `confirm_new_seat=true`. Nothing is charged at that point. Re-sending an invitation doesn't ask
   again. On the free plan, the refusal (`402 subscription_required`) carries the Team plan's
   list prices, and the Team page offers one button that opens the checkout.
4. **No price preview from Paddle.** Since nothing is charged when the admin confirms, a prorated
   preview at that moment would show an amount nobody pays. The question shows the price per
   person per billing period; Paddle prorates it from the day the person can sign in.

## Consequences

- No client can add to the bill without asking first: the server refuses, whoever calls it.
  Scripts that invite people on the Team plan need `confirm_new_seat=true` (docs/api.md, "Seats").
- An invitation sent within a free plan of more than one person (a setting; the default is one)
  is billed once it's accepted, after the account moved to Team, without a second question.
- The price per person comes from the subscription's own price, which Paddle charges per billing
  period. VERIFY with Paddle's sandbox that a yearly price's `unit_price` is the yearly amount;
  the billing page now says "a year" or "a month" after it.
