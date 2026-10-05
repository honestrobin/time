# 0024. A read-only account can still end its connections

- Status: accepted (by Claude on 5 October 2026, following the Robin's Code, "You can always leave"; the maintainer reviews the architecture and may revisit it)
- Date: 2026-10-05
- Spec reference: §1.2.2, §13, AT-6.3

## Context

The spec makes a lapsed account read-only with working export (§1.2.2, AT-6.3), and an account
waiting to be deleted is read-only the same way. In code that's `Member.requireWritable`: every
action that changes the account refuses with `402 account_read_only`, and exports never call it.

The Robin's Code promises Move out on every plan and in every state of an account, with "a list of
everything still connected ... and how to end each one". While an account was read-only, ending a
connection was refused like any other change. An admin had to end Stripe, QuickBooks, Xero or
Storecove at the other service, or wait for the account to be deleted. For Peppol on Honest
Robin's Storecove contract there was no other service to go to, and the account's Peppol ID stayed
registered, so no other access point could take it. Someone else's token could only be stopped by
deactivating that person, which read-only also refuses.

## Decision

Read-only still refuses every change to the account's data and settings, with one exception:
**ending a connection.** These work in every state, for admins:

- disconnecting Stripe, QuickBooks or Xero, and Storecove (Peppol);
- stopping a Harvest sync, and cancelling an import, so it forgets its Harvest token;
- revoking anyone's token or signed-in device in the account (`DELETE /api/v1/account/api_tokens/{id}`),
  and a person revoking their own;
- withdrawing an invitation, so its link stops working (`DELETE /api/v1/people/{id}/invite`).

Connecting, resuming an import, changing a connection's settings, inviting, and deactivating or
bringing back people stay refused while the account is read-only.

Why: leaving must not be harder for an account that has stopped paying or is about to be deleted.
These actions only take access away, from us or from someone else, and each is the way out the
Move out page names.

## Consequences

- The Move out page and the export's README can say, truthfully, that an admin can end each
  connection in every state. Invoice links are the exception: they end only when the account is
  deleted.
- Ending a connection does more than remove access in two places: disconnecting QuickBooks or
  Xero drops invoices and payments not yet pushed, and withdrawing an invitation sets the person
  back to "not invited yet". Inviting them again waits until the account is active.
- `EndConnectionsTest` guards the exception in both read-only states, and checks that connecting
  and resuming are still refused. A new kind of connection needs its end action added there, and
  must not call `requireWritable`.
- Deactivating people stays refused while read-only. A separate change for lapsed accounts
  (`fix/lapsed-back-to-free`) may change that; the Move out wording follows whichever is on main.
