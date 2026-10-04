# 0012. Who may send email, and how much

- Status: accepted
- Date: 2026-10-03
- Spec reference: §11, §15 (security), the security review of 3 October

## Context

Honest Robin sends email on an account's behalf to people outside it: invitations, invoices and
invoice reminders. On Honest Robin Cloud anyone can sign up, so without limits a throwaway account
could use our mail domain to send unwanted mail, and our sending reputation is shared by every
customer. A self-hosted instance sends through its owner's own mail server, to people its owner
chose to let in.

The edition flag may not change product behaviour (decision record 0009, spec §1.2.3), so the
answer can't be "the cloud edition has limits".

## Decisions

1. **Confirmed senders where sign-up is open.** When `HONESTROBIN_SIGNUP_MODE=open`, a new user
   gets a link to confirm their address, and an account emails nobody outside it (invitations,
   invoices, reminders) until one of its active admins has confirmed. Everything else works
   straight away, including adding people without inviting them and marking invoices as sent.
   Accepting an invitation, or signing in with a sign-in link, confirms the address too. The rule
   follows the sign-up mode, not the edition: a self-hosted instance with open sign-up gets it
   as well, and one where only invited people join doesn't need it.
2. **Invitation limits are configuration.** `HONESTROBIN_MAIL_LIMITS_INVITES_PER_DAY` (invitations
   per account in 24 hours) and `HONESTROBIN_MAIL_LIMITS_INVITE_RESEND_COOLDOWN` (how soon the
   same invitation can go out again). Both default to no limit (but see 5). Honest Robin Cloud runs with
   **50 per day** and **10 minutes**: enough for a team moving over from Harvest in one go, too
   little to be worth abusing. Over the limit, the API answers `429` with `invite_limit` or
   `invite_cooldown` and a sentence a person can act on.
3. **Invoice emails and sign-ups** (added after the security review of 3 October):
   `HONESTROBIN_MAIL_LIMITS_INVOICE_RECIPIENTS_PER_DAY` caps invoice email recipients per account
   (default no limit, but see 5; Cloud: 300), and `HONESTROBIN_MAIL_LIMITS_SIGNUPS_PER_HOUR_PER_IP` caps
   sign-ups per IP address where sign-up is open (default 10). Names that appear in emails
   (people, profiles, workspaces) can't contain web addresses. These counters are kept per
   server; with several servers, move them to the database.
4. **Sign-in emails** keep their own limit: three per address in 15 minutes, dropped silently so
   the response never reveals whether an address has an account (unchanged). The confirmation
   email at sign-up and resending it count against the same limit. A new sign-in or reset link
   replaces the earlier ones.
5. **The limits follow the sign-up mode, and count per person** (added after the security review
   of 4 October 2026). Left unset, the invitation and invoice limits take Honest Robin Cloud's
   numbers where sign-up is open (50 invitations, 10 minutes, 300 recipients) and stay off
   elsewhere, so an instance that opens sign-up is never without them, whoever forgets to set
   them. Invitations and invoice emails also count per person, across all their workspaces:
   anyone can create workspaces where sign-up is open, and each new one used to bring a fresh
   allowance. Reminders count against the account's invoice limit and wait when it's reached;
   before, they weren't counted at all. A workspace's legal name, which heads every invoice
   email's subject, can't contain a web address, like the other names. Whether a name belongs
   to the business using it isn't checked: nothing in code can tell reliably. That rests on the
   confirmed address and the limits.

## Consequences

- A cloud customer who needs more invitations in a day writes to support; the limit is a setting,
  so raising it needs no release.
- Tests run with open sign-up, so the test helper confirms each new user's address, which also
  exercises the confirmation flow in every test. Their configuration turns the invitation and
  invoice limits off; the tests of the limits set them.
