-- SPDX-License-Identifier: AGPL-3.0-only
-- Paddle doesn't promise to deliver events in order, and retries them for days: a late retry of an
-- old event could undo a newer one, like bringing a cancelled subscription back. Each subscription
-- remembers when the event it last applied happened; older events are recorded, not applied.
alter table subscriptions add column last_event_at timestamptz;
