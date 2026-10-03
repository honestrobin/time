-- SPDX-License-Identifier: AGPL-3.0-only

-- Live updates (spec §9, AT-4.1): any change to a person's time entries notifies every server,
-- which tells that person's open web apps and extension popups to refresh. Postgres delivers
-- notifications on commit and folds duplicates within a transaction, so bulk changes send one.
create function honestrobin_notify_time_entry() returns trigger
    language plpgsql as
$$
begin
    perform pg_notify('honestrobin_time_entries', coalesce(new.membership_id, old.membership_id)::text);
    return null;
end
$$;
create trigger time_entries_notify
    after insert or update or delete on time_entries
    for each row execute function honestrobin_notify_time_entry();

-- Signing in a device (the browser extension) without typing a password into it (RFC 8628): the
-- device shows a short code, the person approves it in the web app, and the device collects an
-- API token. Only hashes of the device's secret are kept; the token is made when collected.
create table device_authorizations
(
    id               uuid primary key     default uuid_generate_v7(),
    device_code_hash bytea       not null unique,
    user_code        text        not null unique,
    client_name      text        not null,
    status           text        not null default 'pending' check (status in ('pending', 'approved', 'denied', 'used')),
    membership_id    uuid references memberships (id) on delete cascade,
    created_at       timestamptz not null default now(),
    expires_at       timestamptz not null,
    approved_at      timestamptz
);
create index device_authorizations_expires_idx on device_authorizations (expires_at);
