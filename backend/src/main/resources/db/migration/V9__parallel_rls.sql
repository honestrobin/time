-- SPDX-License-Identifier: AGPL-3.0-only
-- The row-level security check only reads settings, which Postgres copies to parallel workers,
-- so it is parallel safe. Marked as such, reports over large accounts can scan in parallel
-- (AT-6.4: a full-history report over a million entries went from 1.6 s to 0.6 s).
alter function honestrobin_rls_ok(uuid) parallel safe;
