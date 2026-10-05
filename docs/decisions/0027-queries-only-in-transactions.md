# 0027. The app's queries run only in a transaction

- Status: accepted (by Claude on 5 October 2026, from the second review of #25, which brought in 0026; the maintainer reviews the architecture and may revisit it)
- Date: 2026-10-05
- Spec reference: §3.4
- Supersedes: in 0002's decision, "Queries outside a transaction see no tenant rows"; in 0026's
  consequences, that reading account data only in a transaction is a convention nothing enforces

## Context

Row-level security keeps accounts apart only in a transaction: `TenantAwareTransactionManager`
switches each one to the role `honestrobin_app` and sets its account (0002). A jOOQ query outside
a transaction got none of this. It ran as the database user itself, which in the Compose setup is a
superuser, and row-level security doesn't apply to a superuser. 0002 said such a query sees no
account rows. That holds only for a database user that doesn't bypass row-level security.

The same held for a query in an after-commit callback. Spring keeps the transaction's connection
until the callbacks have run, but the commit has already ended the role switch and the account.

Nothing refused such a query, so a single forgotten or misplaced `@Transactional` could read and
change every account's data without an error. A read of the code found no production query
outside a transaction. Making jOOQ refuse them found one the reading missed: since 4 October,
editing an invoice ran without a transaction, because its `@Transactional` sat above another
function (#30).

## Decision

1. **jOOQ runs a query only on the connection of an open transaction** that
   `TenantAwareTransactionManager` began: one that switched to the role and set the account, and
   hasn't yet committed or rolled back. `TransactionalConnectionProvider` refuses any other query
   with `QueryOutsideTransaction`, before it reaches the database. That covers a query with no
   transaction at all, a `PROPAGATION_SUPPORTS` scope with no transaction to join, and a callback
   after a commit or a rollback.
2. **We refuse the query rather than run it in a transaction of its own,** which would hide the
   mistake. Without an account, such a query would quietly see nothing. With one, each
   statement would commit on its own, so a refused change would keep half of what it did, as the
   invoice edit did. A refusal is an error in the log and a failing test.

## Consequences

- A forgotten `@Transactional` fails loudly, in tests and in production, instead of reading as a
  superuser: a request answers 500, and a job, webhook or listener fails with an error in the log.
  The fix is to run the code in a transaction: `@Transactional`, `Tx.run` or `Tx.system`. In an
  after-commit callback, those join the transaction that has just ended and are refused too; the
  code there needs a new transaction (`PROPAGATION_REQUIRES_NEW`), as `BillingService` uses.
- Some work still runs as the database user outside a transaction, by design, and none of it uses
  jOOQ or reads account data: Flyway's migrations (on connections of their own since #28), the job
  scheduler's own table, listening for live updates, and the first part of the startup check,
  which looks up the role.
- Tests use the app's own `DSLContext`, so they run their queries in `tx.run` or `tx.system` too.
  What only the database user may do, such as creating a role, they do over plain JDBC.
- `RowLevelSecurityTest::a query outside a transaction is refused` guards it, on the ledger row
  "Nobody sees another account's data".
