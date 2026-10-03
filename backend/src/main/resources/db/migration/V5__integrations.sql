-- SPDX-License-Identifier: AGPL-3.0-only
-- Connections to outside services (spec §4 `integrations`): Stripe first, e-invoicing and accounting later.
create table integrations
(
    id                    uuid primary key     default uuid_generate_v7(),
    account_id            uuid        not null references accounts (id) on delete cascade,
    kind                  text        not null check (kind in ('stripe', 'storecove', 'qbo', 'xero')),
    -- api_key: the account's own keys; connect: authorised through the platform (Stripe Connect, OAuth)
    mode                  text        not null check (mode in ('api_key', 'connect')),
    status                text        not null default 'connected' check (status in ('connected', 'error')),
    -- secrets (API keys, OAuth tokens) as encrypted JSON; never returned by the API
    credentials_encrypted text,
    -- the account at the provider, e.g. a Stripe account id
    external_account_id   text,
    display_name          text,
    settings              jsonb       not null default '{}'::jsonb,
    last_error            text,
    connected_by          uuid references memberships (id) on delete set null,
    connected_at          timestamptz not null default now(),
    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now(),
    unique (account_id, kind)
);
create index integrations_external_idx on integrations (kind, external_account_id);
select honestrobin_manage_table('integrations', true);

-- An online payment is recorded once, however many times the provider reports it.
create unique index payments_external_uq on payments (invoice_id, external_id) where external_id is not null;
