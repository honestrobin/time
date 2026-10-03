# 0003. Audit log written by a database trigger

- Status: accepted
- Date: 2026-09-30
- Spec reference: §3.5 ("All mutations write an audit_log row")

## Context

Writing audit rows from service code is easy to forget, and every missed call site breaks the guarantee.

## Decision

A generic `honestrobin_audit()` trigger on every business table writes `audit_log` rows with the actor, IP, action (`<table>.<insert|update|delete>`) and a per-column `[before, after]` diff. The actor comes from the transaction-local settings described in ADR 0002. Secrets (`password_hash`, `token_hash`, encrypted credentials) and churn columns (`updated_at`, `last_used_at`, `last_login_at`) are excluded, and updates that change only those columns are not logged. Services attach a human reason (for example, an admin unlocking entries) with `AuditService.withReason()`. Events that are not row changes, such as exports, are recorded explicitly with `AuditService.record()`. The application role has no UPDATE/DELETE/TRUNCATE privilege on `audit_log`.

## Consequences

Bulk imports produce one audit row per imported record, which roughly doubles write volume during an import. This is acceptable because imports are bounded by Harvest's rate limits. Account hard-deletion must run with owner privileges to remove audit rows.
