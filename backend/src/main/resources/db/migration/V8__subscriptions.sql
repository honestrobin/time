-- SPDX-License-Identifier: AGPL-3.0-only
-- Honest Robin Cloud subscriptions through Paddle (spec §14). The tables exist in both editions
-- (one schema); only the cloud edition writes to them.

create table subscriptions
(
    id                      uuid primary key     default uuid_generate_v7(),
    account_id              uuid        not null unique references accounts (id) on delete cascade,
    provider                text        not null default 'paddle' check (provider in ('paddle')),
    external_id             text        not null unique,
    external_customer_id    text,
    -- the Paddle price the subscription is on; seat changes keep using it, which keeps the price
    external_price_id       text        not null,
    plan                    text        not null default 'team' check (plan in ('team')),
    billing_interval        text        not null check (billing_interval in ('month', 'year')),
    status                  text        not null check (status in ('trialing', 'active', 'past_due', 'paused', 'canceled')),
    seats                   integer     not null check (seats >= 1),
    currency                char(3)     not null,
    -- Price lock (spec §14): the unit price per seat when the subscription started. It may go
    -- down (a cheaper price, a discount) but never up; see the trigger below.
    locked_unit_price_minor bigint      not null check (locked_unit_price_minor >= 0),
    current_period_end      timestamptz,
    cancel_at               timestamptz,
    canceled_at             timestamptz,
    created_at              timestamptz not null default now(),
    updated_at              timestamptz not null default now()
);
select honestrobin_manage_table('subscriptions', true);

create or replace function honestrobin_price_lock() returns trigger
    language plpgsql as
$$
begin
    if new.locked_unit_price_minor > old.locked_unit_price_minor then
        raise exception 'price_lock: a subscription''s unit price can''t be raised (% to %)', old.locked_unit_price_minor, new.locked_unit_price_minor
            using errcode = 'check_violation';
    end if;
    if new.currency <> old.currency then
        raise exception 'price_lock: a subscription''s currency can''t change' using errcode = 'check_violation';
    end if;
    return new;
end
$$;

create trigger subscriptions_price_lock
    before update on subscriptions
    for each row execute function honestrobin_price_lock();

-- Paddle retries webhooks; each event is handled once.
create table billing_events
(
    event_id    text primary key,
    event_type  text        not null,
    account_id  uuid references accounts (id) on delete cascade,
    received_at timestamptz not null default now()
);
select honestrobin_manage_table('billing_events', true, false);
