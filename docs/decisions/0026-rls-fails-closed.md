# 0026. The app refuses to start when row-level security can't keep accounts apart

- Status: accepted (by Claude on 5 October 2026, from the architecture review of that day; the maintainer reviews the architecture and may revisit it); that nothing refuses a query outside a transaction is superseded by 0027
- Date: 2026-10-05
- Spec reference: §3.4
- Supersedes: the last point of 0002's decision ("If the role cannot be created ..., the app logs
  a warning and relies on FORCE RLS; a startup self-check reports whether RLS is effective")

## Context

Accounts are kept apart by row-level security, and every transaction switches to the role
`honestrobin_app` so the policies hold even for a superuser (0002). The Compose setup connects as
a superuser. When the role was missing, the app started anyway: it logged a warning, ran every
transaction as the login user, and the startup check logged a second warning. For a superuser,
row-level security then does nothing, and each query's own `account_id` filter was all that kept
one account's data from another.

The role goes missing in an ordinary way. A database dump holds the rights given to the role but
not the role itself, so a restore into a fresh server without `CREATE ROLE honestrobin_app` first
finishes without it. A restore is done under pressure, and a warning in the log is easy to miss.

## Decision

1. **Every transaction switches to the configured role, or doesn't begin.** There's no fallback to
   the login user: when the role is missing, PostgreSQL refuses the switch and the transaction
   fails.
2. **At startup, before the web server takes requests, the app checks** that the role exists, that
   the database user may switch to it, that a transaction running as it doesn't bypass row-level
   security (no superuser, no `BYPASSRLS`), and that it has its rights on the tables. If any of
   these fails, the app refuses to start. Spring Boot reports what's wrong and how to fix it,
   without a stack trace, pointing to `docs/self-host.md`.
3. **Background jobs start after the check,** once the application context is ready
   (`db-scheduler.delay-startup-until-context-ready`), so no job runs before the app has shown that
   it keeps accounts apart.
4. **No setting turns this off.** The one setting there is, `honestrobin.db.app-role`, can be left
   blank so that transactions run as the login user itself. The check still applies: that user
   must not bypass row-level security, and FORCE ROW LEVEL SECURITY on every account table then
   holds it to the policies.

## Consequences

- A database where the role can't be created and nobody created it no longer runs on FORCE RLS
  alone: the app doesn't start until the role exists. Nothing has been released yet, so no
  installation depends on the old behaviour.
- A role created after the tables, after a restore without it or on a database whose user
  couldn't create it, has none of its rights. The check names this case on its own, and
  `docs/self-host.md` (Database role) gives the rights the migrations would have given. A new
  grant to the role in a migration goes there too.
- Some work runs as the login user by design, outside any transaction: Flyway's migrations, the
  job scheduler's own table, and listening for live updates. None of it reads account data.
  Queries outside a transaction get no role and no account either, so account data is read only
  in a transaction, as 0002 says. That's a convention: nothing refuses such a query yet.
- `RowLevelSecurityTest` guards it, against the same PostgreSQL the other tests use, whose user
  is a superuser as in the Compose setup. "The app refuses to start when row-level security can't
  hold" runs the startup check for each case; "the app doesn't start without its database role"
  starts a second app without it.
