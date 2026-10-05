# 0002. Tenant isolation with transaction-scoped settings and SET ROLE

- Status: accepted; the last point of the decision (the fallback when the role cannot be created) is superseded by 0026, and the first sentence of the third (queries outside a transaction see no tenant rows) by 0027
- Date: 2026-09-30
- Spec reference: §3.4

## Context

Tenancy is enforced in the repository layer, with Postgres row-level security as defence in depth. The stock docker-compose Postgres user is a superuser, and superusers bypass RLS.

## Decision

- Every tenant table has `account_id` and a `tenant_isolation` policy using `honestrobin_rls_ok(account_id)`, with `FORCE ROW LEVEL SECURITY` so the table owner is also subject to it.
- `TenantAwareTransactionManager` starts every transaction with `set_config('role', 'honestrobin_app', true)` (an unprivileged `NOLOGIN` role created by migration V1), then sets the transaction-local GUCs `honestrobin.account_id`, `honestrobin.actor_id`, `honestrobin.actor_type`, `honestrobin.ip` and `honestrobin.rls_bypass`.
- Queries outside a transaction see no tenant rows. Cross-tenant system work (sign-in, token lookup, schedulers) runs through `DbContext.system {}` / `Tx.system {}`, which sets `rls_bypass`.
- Flyway runs as the login role with `honestrobin.rls_bypass = on`.
- If the role cannot be created (no CREATEROLE privilege), the app logs a warning and relies on FORCE RLS; a startup self-check reports whether RLS is effective.

## Consequences

A forgotten `WHERE account_id = ?` cannot leak data across tenants. RLS does not protect against SQL injection, because GUCs can be set by any SQL; jOOQ's bind parameters handle that. Every data access must happen in a transaction, which is the convention anyway.
