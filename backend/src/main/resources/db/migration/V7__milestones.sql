-- SPDX-License-Identifier: AGPL-3.0-only
-- Firsts in an account's life (first timer, first invoice sent): recorded once, so funnel
-- events fire once per account, also after an import brought older data in.

create table account_milestones
(
    account_id uuid        not null references accounts (id) on delete cascade,
    key        text        not null,
    reached_at timestamptz not null default now(),
    primary key (account_id, key)
);
select honestrobin_manage_table('account_milestones', true, false);
