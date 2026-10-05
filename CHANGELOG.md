# Changelog

What changes in how you work with Time, newest first, in plain words. A change is listed here
before it reaches Honest Robin Cloud. Each release also gets a record of what was checked and what
wasn't.

## Unreleased

Time hasn't had its first release yet. Until it does, changes people will notice are listed here
as they're made.

- Honest Robin Cloud bills a person from the moment they can sign in. An invitation is free until
  it's accepted; checkout and the billing page now count the same people.
- On the Team plan, inviting someone or bringing someone back shows what it adds to your bill, and
  goes ahead only when you say yes. On the free plan, the Team price and the button to start it are
  right where you invite.
- For scripts on Honest Robin Cloud's Team plan: requests that give someone sign-in access answer
  `409 seat_confirmation_required` with the price, until they're sent again with
  `confirm_new_seat=true` (`docs/api.md`, "Seats").
- Help is on every page: at the foot of the menu, in the bar on a phone, or press `?`. It shows
  where to write to us first. A person reads every message and answers it: yes, no or later, and
  why. The keyboard shortcuts moved there from the account menu.
