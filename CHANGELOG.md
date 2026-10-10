# Changelog

What changes in how you work with Time, newest first, in plain words. A change is listed here
before it reaches Honest Robin Cloud. Each release also gets a record of what was checked and what
wasn't.

## Unreleased

Time hasn't had its first release yet. Until it does, changes people will notice are listed here
as they're made.

- Track is redrawn, and the menu moved to a bar across the top, with Help and Settings at its
  right end. Today's timer asks what you're working on: type a note and press Enter (or N), and it
  runs on the project you used last; S stops it. While it runs, the time and the project are in the
  browser tab and, on every other page, in the top bar. Each day of the week shows its hours and a
  bar measured against the week's longest day, and the day you're looking at stays shaded however
  you got there. On a wider screen, entries show when they ran, and Continue starts a new entry with
  the same project and note, so the earlier one keeps its true times (as pressing Start again
  would; with rounding on, each entry is rounded on its own). If a timer can't start, what you
  typed stays.
- A part of Time that's switched off now also stops what it would do without being asked, since
  nobody could see it or turn it off: no budget alerts, no invoice reminders, no Harvest sync, and
  approvals count as off, so a week sent for approval is open to edit again and reminders don't ask
  for it. Approved time stays locked, and an approved week can take new entries while approvals
  are off. Its data stays, and comes back with the switch. The team stays while an account has
  other people, even with its switch off: in the menu, on the billing page and in Move out, so
  anyone can still be deactivated or invited again. With just you and the team switched off, the
  billing page no longer offers a Team plan.
- Time now shows a small core: tracking time, clients and projects, the time reports, and your
  data (export, Move out, settings and help). Invoices, e-invoicing, online payments, accounting,
  expenses, the shared task list, the team, approvals, budgets and the Harvest import are built
  and still work, and they're switched off by default while we make the core a joy to use. They
  come back one at a time. Nothing is deleted: the API and the export cover everything, and Move
  out still links to wherever a connection is ended. Self-hosted: to keep everything as it was,
  set `HONESTROBIN_FEATURES=all`, or name the parts you use (`docs/self-host.md`). The audit log
  and the API reference moved out of the menu, to Settings → Account and Profile.
- Adding a new project, client or task while tracking time now saves all of it with the entry, or
  none of it. Before, when the entry was refused (for example, more than 24 hours), the project,
  client and task were created anyway, and the dialog switched to the new project, so the names
  you had typed were no longer there to correct. Now nothing is created until everything is right,
  what you typed stays, and the problem shows at its field, where it has one.
- Unticking Billable on a new entry now works. Before, the box was ignored and the entry was saved
  as billable, so it could end up on an invoice; only editing the entry afterwards changed it. If
  you unticked it on a new entry, check that entry.
- A new client with the name of one you already have in another currency is no longer quietly
  used for the new project: Time says so at the name, so a rate is never saved in a currency you
  didn't see. Pick the client from the list instead.
- Editing an invoice runs in a single transaction again. From 4 October until this change it
  didn't. With the Compose setup's default database user, someone allowed to invoice in one
  account could read and change an invoice of another account on the same instance, if they knew
  its id: anything on it the client sees, and, for an admin of their own account, its payment
  instructions. Reading a sent invoice also gave them its public link, which still works and which
  the app can't change yet. Removing a line from such an invoice freed the time and expenses on it,
  so they could be invoiced again. The app shows an invoice's id only to people in its own account,
  so they'd have needed it from somewhere else, for example from having worked there. In every
  account, an edit that was refused could keep part of what it changed: an invoice could be left
  with lines that don't add up to its total, or marked paid with a total below its payments. The
  audit log recorded these edits without the person or their address, and reads not at all.
  Update; if your instance has more than one account, pull request #30 has the queries that show
  what changed.
- Self-hosted: after every start, for up to 30 minutes, two of the app's database connections kept
  row-level security's bypass switched on, left over from the migrations. A transaction sets the
  bypass again for itself, so the leftover mattered only to a query outside one, on a database user
  that row-level security applies to. Row-level security never applies to the Compose setup's
  default user outside a transaction, because it's a superuser, so there the leftover changed
  nothing. The migrations now get connections of their own; updating is all you need to do.
- Self-hosted: when the database can't keep accounts apart, for example after a restore without
  the app's database role, the app refuses to start, and its log says how to fix it. Before, it
  started with only a warning, and with the default database user only each query's own filter
  kept one account's data from another. The restore steps in `docs/self-host.md` now create the
  role first.
- On Honest Robin Cloud, Move out's list of what's still connected includes a Team subscription,
  in the export's README too: its billing interval, whether it's active, past due or cancelled,
  and how to cancel it. Before, the README only said where to look.
- If Paddle ever reports a higher price than your locked one, your lock stays, the billing page
  shows both prices, and every admin gets an email. If Paddle charges more, we refund the
  difference, by hand for now.
- Cancel the Team plan in the app, in two clicks, on the billing page. It ends with the period
  you've paid for, and nothing changes before then. Until that day, you can keep it with one more
  click. While a payment is past due, that period isn't paid for, so the plan ends at once.
- On Honest Robin Cloud, an account that turned read-only when its Team plan ended can deactivate
  people. As soon as no more people can sign in than the free plan holds, it works again. The
  billing page and the read-only notice say how.
- After a Team plan ends, accepting an invitation that the free plan has no room for is refused,
  and the person invited is asked to talk to whoever invited them. Before, they got in and the
  account turned read-only for everyone.
- While an account is read-only (lapsed, or waiting to be deleted), an admin can still end every
  connection: disconnect Stripe, QuickBooks, Xero and Peppol, stop a Harvest sync or cancel a
  stopped import, revoke anyone's token or signed-in device, and withdraw an invitation. The Move
  out page has Revoke and Withdraw buttons for these, and the Import page lists every older import
  that still keeps a Harvest token, each with its own Stop or Cancel. Connecting, changing
  anything and inviting people still wait until the account is active again, and so does
  deactivating people in an account waiting to be deleted.
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
