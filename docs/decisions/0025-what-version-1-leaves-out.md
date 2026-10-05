# 0025. What version 1 leaves out of the specification

- Status: accepted (by Claude on 5 October 2026, as a record of what isn't built; the maintainer reviews the architecture and may revisit it)
- Date: 2026-10-05
- Spec reference: §2.1, §4, §10, §11, §14, §15

## Context

The [specification](../spec.md) says what version 1 should do, and where the build differs, a
decision record is meant to say why. Until today, `ROADMAP.md` said everything in the
specification was built and tested. Some of it isn't, and no record said so.

The architecture review of 5 October 2026 named five gaps. Each was checked against the code that
day, and one more was found while checking (the switcher offer). Nobody has gone through the whole
specification line by line, so there may be more. This record is about the software: how Honest
Robin Cloud is run (§15's backups, status page and uptime) isn't in it.

## Decision

Version 1, as it is, doesn't have the following. They weren't built, and no record says any of
them was left out on purpose. Each says what the specification asked and what Time does instead.

1. **Sign-in with Google and Microsoft** (§2.1, §11). Time has a password, a sign-in link by
   email, and two-factor sign-in, which an account can require. The sign-in settings the app
   reads (`oauth_providers` in `GET /api/v1/auth/config`) list none. Adding it would take an app
   registered with Google and with Microsoft for each instance, and both companies on
   [companies.md](../companies.md), since they would see each time someone signs in with them.

2. **A limit on API requests per token** (§10: "generous and published, e.g. 600 requests a
   minute per token", with `429` and `Retry-After`). Time limits sign-in attempts, sign-ups,
   sign-in links, device codes, invitation and invoice mail, and imports, but not how many
   requests one token sends. Until it does, one token can keep a server busy for every account on
   it, and only the token's own account can revoke it. The operator of a self-hosted server can
   limit requests at the reverse proxy.

3. **A key service (KMS) for Honest Robin Cloud's secrets** (§15). Both editions keep the key
   that encrypts stored credentials, two-factor secrets and import tokens in a file on the data
   volume (`secrets.key`), or in the `HONESTROBIN_SECRETS_KEY` variable. Whoever has both the key
   and the database can read those secrets. A backup holds both
   ([self-host.md](../self-host.md)), so it needs guarding like the server itself.

4. **Code scanning (SAST) in CI** (§15). Not set up. What runs instead: Dependabot alerts and
   monthly update pull requests for dependencies (the dependency scanning §15 also asks for), and
   security regression tests (`SecurityRegressionTest`).

5. **Keeping each invoice as it was sent** (§4: a stored document for each Peppol transmission).
   The export, the email and its reminders, the public link and Peppol each make the invoice again
   from today's data, so an invoice edited since, or sent before the company's address changed,
   comes out different from what the client received. `PROMISES.md` and
   [export-format.md](../export-format.md) already say so.

6. **The switcher offer** (§14: free months for a team leaving Harvest before its term ends,
   applied by hand). Time's billing has no offers or coupons. It depends on Honest Robin Cloud's
   prices, which aren't decided yet.

## Consequences

- `ROADMAP.md` no longer says that everything in the specification is built, and points here;
  `README.md` no longer calls version 1 feature-complete.
- Nothing above changes a promise in `PROMISES.md`.
- When one of these is built, or dropped for good, a new record says so, and the roadmap follows.
