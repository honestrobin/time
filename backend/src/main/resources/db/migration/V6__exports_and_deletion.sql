-- SPDX-License-Identifier: AGPL-3.0-only
-- Full account exports and account deletion (spec §13).

create table account_exports
(
    id           uuid primary key     default uuid_generate_v7(),
    account_id   uuid        not null references accounts (id) on delete cascade,
    requested_by uuid references memberships (id) on delete set null,
    status       text        not null default 'queued' check (status in ('queued', 'running', 'ready', 'failed', 'expired')),
    storage_key  text,
    filename     text,
    size         bigint,
    sha256       text,
    error        text,
    started_at   timestamptz,
    finished_at  timestamptz,
    -- the file is removed after this; the row stays as a record that an export was made
    expires_at   timestamptz,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now()
);
create index account_exports_account_idx on account_exports (account_id, created_at desc);
select honestrobin_manage_table('account_exports', true);

-- Accounts awaiting deletion, found by the daily deletion job.
create index accounts_deletion_idx on accounts (deletion_requested_at) where status = 'pending_deletion';

-- The audit log stays append-only for the application (no DELETE grant). The deletion job
-- removes the log of an account it deletes for good through this function, which runs as the
-- table owner and only in the system context (RLS bypassed).
create policy audit_purge on audit_log for delete using (coalesce(current_setting('honestrobin.rls_bypass', true), '') = 'on');

create or replace function honestrobin_purge_audit(p_account_id uuid) returns bigint
    language plpgsql
    security definer
    set search_path = public as
$$
declare
    v_count bigint;
begin
    if coalesce(current_setting('honestrobin.rls_bypass', true), '') <> 'on' then
        raise exception 'honestrobin_purge_audit runs only in the system context';
    end if;
    delete from audit_log where account_id = p_account_id;
    get diagnostics v_count = row_count;
    return v_count;
end
$$;
revoke all on function honestrobin_purge_audit(uuid) from public;

do
$$
begin
    if exists (select 1 from pg_roles where rolname = 'honestrobin_app') then
        grant execute on function honestrobin_purge_audit(uuid) to honestrobin_app;
    end if;
end
$$;
