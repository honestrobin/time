# Changelog

What changes in how you work with Time, newest first, in plain words. A change is listed here
before it reaches Honest Robin Cloud. Each release also gets a record of what was checked and what
wasn't.

## Unreleased

Time hasn't had its first release yet. Until it does, changes people will notice are listed here
as they're made.

- While an account is read-only (lapsed, or waiting to be deleted), an admin can still end every
  connection: disconnect Stripe, QuickBooks, Xero and Peppol, stop a Harvest sync or cancel a
  stopped import, revoke anyone's token or signed-in device, and withdraw an invitation. The Move
  out page has Revoke and Withdraw buttons for these, and the Import page lists every older import
  that still keeps a Harvest token, each with its own Stop or Cancel. Connecting, changing
  anything, and deactivating or inviting people still wait until the account is active again.
- Move out, in Settings → Account: one step gets everything ready for leaving, and changes
  nothing in your account. You get one zip of all your data, with every invoice as a PDF and as an
  e-invoice where it has the data. The Move out page says where you can go next, and lists
  everything still connected to the account (tokens, devices, invitations, outside services,
  invoice links), with how to end each. Invoice PDFs in the export now have Factur-X inside where
  your clients get it. They're drawn from today's data, as before, so an invoice that changed
  since it was sent differs from the one your client received.
- Honest Robin Cloud bills a person from the moment they can sign in. An invitation is free until
  it's accepted; checkout and the billing page now count the same people.
- On the Team plan, inviting someone, sending an invitation again or bringing someone back shows
  what it adds to your bill, and goes ahead only when you say yes to that price. On the free plan,
  the Team price and the button to start it are right where you invite.
- A Harvest sync no longer brings back someone you deactivated here. It lists them instead, so you
  can bring them back yourself.
- For scripts on Honest Robin Cloud's Team plan: requests that give someone sign-in access answer
  `409 seat_confirmation_required` with the price, until they're sent again with that price
  (`confirm_unit_price_minor`, `confirm_currency`, `confirm_interval`; `docs/api.md`, "Seats").
- Help is on every page: at the foot of the menu, in the bar on a phone, or press `?`. It shows
  where to write to us first. A person reads every message and answers it: yes, no or later, and
  why. The keyboard shortcuts moved there from the account menu.
