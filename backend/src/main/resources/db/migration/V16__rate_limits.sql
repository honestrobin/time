-- SPDX-License-Identifier: AGPL-3.0-only
-- Rate limits shared by every server of an instance (security review, 3 October): sign-in
-- failures, sign-in emails, sign-ups, invoice emails, imports. One row per counted event;
-- old rows are purged by the session-cleanup job. Unlogged: a crash may reset the counters,
-- which is fine for limits and spares the write-ahead log.
create unlogged table rate_limit_events
(
    bucket text        not null,
    at     timestamptz not null
);
create index rate_limit_events_bucket_idx on rate_limit_events (bucket, at);
