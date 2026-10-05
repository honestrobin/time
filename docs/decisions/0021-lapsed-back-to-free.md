# 0021. A lapsed account can get back to the free plan

- Status: accepted
- Date: 2026-10-05
- Spec reference: §14, AT-6.3
- Supersedes: decision 5 of 0013 in part (how a lapsed account gets out), and settles the open
  question in 0020's consequences (an invitation accepted after a subscription ended)

## Context

Decision record 0013 turns an account read-only (`lapsed`) when its subscription ends and more
people can sign in than the free plan allows. Two things in that broke the Robin's Code ("We trust
you too": nothing you already have ever stops working):

- A lapsed account couldn't deactivate anyone, because changing a person needs a writable
  account. Its only ways out were paying again or leaving.
- An invitation accepted after the subscription ended charged nothing, but it could take the
  account past the free plan, and the seat job then turned the whole account read-only. One
  person joining stopped everyone's work.

## Decision

1. **A lapsed account's admins can deactivate people, and only that.** `PATCH
   /api/v1/people/{id}` with `{"is_active": false}` and nothing else works while the account is
   lapsed. Any other change, also one sent together with it, is still refused with `402
   account_read_only`. Other read-only accounts (waiting to be deleted) don't change.
2. **Back within the free plan, the account works again at once.** The deactivation tells billing
   in the same transaction (`SeatFreed`), and an account with no more people who can sign in than
   the free plan holds is active again before the request returns. The seat job never lapses it
   again unless someone can sign in beyond the free plan.
3. **Joining never turns an account read-only.** When someone accepts an invitation after the
   subscription ended (cancelled or paused), and the free plan has no room for them, the accept is
   refused with `409 invitation_needs_team_plan` and a plain message for the person invited: their
   invitation needs the workspace's Team plan, so they should ask whoever invited them. Nothing
   changes: not the account, not the invitation, and the link keeps working until it expires. Once
   Team runs again, the same link works and the person is billed from that moment (0020).
   Accepting is asked through the seat gate, like every other way to sign-in access.
4. **What isn't refused:** on the Team plan, the yes to the price was given when the invitation
   went out, or at checkout (0020, decision 4), so accepting asks nothing more. An account that
   never had a subscription doesn't turn read-only, so its invitations aren't refused either.
5. **The billing page and the read-only notice say how,** with the free plan's size: "Keep up to N
   people who can sign in, or start Team again." Deactivating someone keeps everything they
   tracked, and export works throughout.

## Consequences

- A lapsed account can leave read-only without paying, in as many steps as people it deactivates.
  It can still start Team again instead, and checkout counts the people who can sign in.
- Two accepts at the same moment take turns (an advisory lock for each account), so they can't
  both take the last free seat.
- The invited person learns that the workspace's Team plan isn't active. That's what they need to
  know to act, and nothing more about the account.
