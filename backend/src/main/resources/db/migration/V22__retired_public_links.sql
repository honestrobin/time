-- SPDX-License-Identifier: AGPL-3.0-only
-- Links to the public invoices of deleted accounts (security review, 4 October 2026). Deleting an
-- account freed its invoices' links, so an export that carried them, crafted or not, could bring
-- them back with other content behind them, where clients still have them in their inbox. Each
-- link's SHA-256 is kept, nothing else; an imported invoice with a retired link gets a new one.
create table retired_public_links
(
    token_sha256 bytea primary key,
    retired_at   timestamptz not null default now()
);
