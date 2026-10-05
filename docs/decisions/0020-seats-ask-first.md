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
   own, as not billed. The free plan's limit still counts invitations (`SeatCounter.claimed`),
   because accepting one would take the person past it.
2. **Billing starts when someone can sign in.** When an invitation is accepted, or a person with
   sign-in access is brought back, Paddle is told once that change is saved (an after-commit
   listener, `BillingService.onSeatTaken`), prorated from that moment. A change that rolls back
   charges nothing. If Paddle can't be reached, the job sends it within 15 minutes; the person
   can sign in either way. The job also lowers the count when people are deactivated. Each seat
   change claims the new count in the database before Paddle is asked, so the job and an accept
   at the same moment can't both send it.
3. **Asked first, at the moment you act, with the price.** On the Team plan, every request that
   gives someone sign-in access (an invitation, sending it again, an invitation link, bringing
   someone back) is refused with `409 seat_confirmation_required` and the price of one more
   person, until it's sent again with that same price (`confirm_unit_price_minor`,
   `confirm_currency`, `confirm_interval`). A yes to a price that's no longer right is refused
   with the price now. Nothing is charged at that point. Bringing someone back into a seat that's
   still paid for (deactivated less than 15 minutes ago, before the job lowered the count) asks
   nothing, because nothing more is charged. On the free plan, the refusal
   (`402 subscription_required`, also for sending an invitation again) carries the Team plan's
   list prices, and the Team page offers one button that opens the checkout.
4. **Invitations already waiting when a subscription starts are part of the yes to it.** They can
   be there from a free plan of more than one person, from before Paddle was set up on an
   instance, or from an earlier subscription that ended. The billing page and the Team page show
   them before checkout, with what each will cost once accepted, so starting the subscription is
   the yes to them, and accepting one later asks nothing more.
5. **Harvest never brings anyone back.** Neither an import run again nor the sync every 15 minutes
   reactivates someone deactivated in Time. They stay deactivated, and the run lists them ("active
   in Harvest but deactivated here"), so bringing them back is an admin's choice in Time, with the
   question above.
6. **No price preview from Paddle.** When an invitation is confirmed, nothing is charged, and the
   charge comes on a day nobody knows yet, so the question shows the price for one billing period
   and says that on the day billing starts, Paddle charges for what's left of the current period,
   at most that price.

## Consequences

- No request to Time adds a paid seat without a yes to the current price, whoever calls it:
  the app, the API or the extension. Its limits: invitations waiting when a subscription starts
  are agreed to at checkout, not one by one (decision 4); a change made in Paddle itself, in its
  dashboard or customer portal, doesn't go through Time at all.
- Scripts that invite people on the Team plan need the two steps in docs/api.md, "Seats".
- An invitation accepted while the account has no subscription charges nothing. If that takes the
  account past the free plan after a subscription ended, the account turns read-only as 0013
  decision 5 says; whether accepting should be refused instead is open.
- The price of one more person comes from the subscription's own price, which Paddle charges per
  billing period. VERIFY with Paddle's sandbox that a yearly price's `unit_price` is the yearly
  amount, that `prorated_immediately` charges what's left of the period on the day of the change,
  and that sending the same quantity twice charges nothing.
