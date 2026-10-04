-- SPDX-License-Identifier: AGPL-3.0-only
-- Tokens from device sign-in (the browser extension) expire when unused for a while: each use moves
-- expires_at forward by idle_expiry_days. A forgotten or phished token stops working on its own.
alter table api_tokens add column idle_expiry_days integer check (idle_expiry_days > 0);
