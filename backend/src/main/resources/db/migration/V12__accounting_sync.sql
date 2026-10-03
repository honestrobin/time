-- SPDX-License-Identifier: AGPL-3.0-only
-- Pushing invoices and payments to QuickBooks Online and Xero (spec §8): a queue with retries
-- and a visible error state. What was created where is kept in external_links.

create table accounting_sync_items
(
    id              uuid primary key     default uuid_generate_v7(),
    account_id      uuid        not null references accounts (id) on delete cascade,
    provider        text        not null check (provider in ('qbo', 'xero')),
    entity_type     text        not null check (entity_type in ('invoice', 'payment')),
    entity_id       uuid        not null,
    status          text        not null default 'pending' check (status in ('pending', 'done', 'failed')),
    attempts        integer     not null default 0,
    last_error      text,
    next_attempt_at timestamptz not null default now(),
    external_id     text,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    unique (account_id, provider, entity_type, entity_id)
);
create index accounting_sync_due_idx on accounting_sync_items (next_attempt_at) where status = 'pending';
select honestrobin_manage_table('accounting_sync_items', true, false);
