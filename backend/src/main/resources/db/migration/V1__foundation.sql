-- SPDX-License-Identifier: AGPL-3.0-only
-- Foundation: helper functions, tenancy (RLS), audit log, users, accounts, memberships, sessions.

-- ---------------------------------------------------------------------------
-- Helpers
-- ---------------------------------------------------------------------------

-- UUIDv7 (RFC 9562): 48-bit unix ms timestamp + random. Postgres 16 has no built-in.
create or replace function uuid_generate_v7() returns uuid
    language plpgsql volatile as
$$
declare
    ts_ms bytea := substring(int8send(floor(extract(epoch from clock_timestamp()) * 1000)::bigint) from 3);
    bytes bytea := uuid_send(gen_random_uuid());
begin
    bytes := overlay(bytes placing ts_ms from 1 for 6);
    bytes := set_byte(bytes, 6, (b'0111' || get_byte(bytes, 6)::bit(4))::bit(8)::int);
    return encode(bytes, 'hex')::uuid;
end
$$;

create or replace function honestrobin_set_updated_at() returns trigger
    language plpgsql as
$$
begin
    new.updated_at := now();
    return new;
end
$$;

-- Row-level security predicate. The application sets these per transaction
-- (see TenantAwareTransactionManager). Without them, tenant tables are invisible.
create or replace function honestrobin_rls_ok(p_account_id uuid) returns boolean
    language sql stable as
$$
select coalesce(current_setting('honestrobin.rls_bypass', true), '') = 'on'
    or p_account_id = nullif(current_setting('honestrobin.account_id', true), '')::uuid
$$;

-- The application connects with `SET ROLE honestrobin_app` so RLS applies even when the
-- login role is a superuser (as in the stock docker-compose setup).
do
$$
begin
    if not exists (select 1 from pg_roles where rolname = 'honestrobin_app') then
        create role honestrobin_app nologin;
    end if;
    execute format('grant honestrobin_app to %I', current_user);
exception
    when insufficient_privilege then
        raise notice 'Could not create/grant role honestrobin_app; RLS will rely on FORCE ROW LEVEL SECURITY only';
end
$$;

-- ---------------------------------------------------------------------------
-- Audit log (append-only). Populated by a generic trigger on every audited table
-- so no code path can mutate data without leaving a record.
-- ---------------------------------------------------------------------------

create table audit_log
(
    id            uuid primary key     default uuid_generate_v7(),
    account_id    uuid,
    actor_user_id uuid,
    actor_type    text        not null default 'system' check (actor_type in ('user', 'api_token', 'system')),
    action        text        not null,
    entity_type   text        not null,
    entity_id     uuid,
    diff          jsonb       not null default '{}'::jsonb,
    reason        text,
    ip            text,
    at            timestamptz not null default now()
);
create index audit_log_account_at_idx on audit_log (account_id, at desc);
create index audit_log_entity_idx on audit_log (entity_type, entity_id);

create or replace function honestrobin_audit() returns trigger
    language plpgsql as
$$
declare
    v_old        jsonb;
    v_new        jsonb;
    v_diff       jsonb;
    v_account_id uuid;
    v_entity_id  uuid;
    v_excluded   text[] := array ['updated_at', 'last_used_at', 'last_login_at', 'password_hash', 'token_hash', 'credentials_encrypted', 'token_encrypted', 'totp_secret_encrypted'];
begin
    if coalesce(current_setting('honestrobin.audit_disabled', true), '') = 'on' then
        return null;
    end if;

    if tg_op in ('UPDATE', 'DELETE') then
        v_old := to_jsonb(old) - v_excluded;
    end if;
    if tg_op in ('INSERT', 'UPDATE') then
        v_new := to_jsonb(new) - v_excluded;
    end if;

    select coalesce(jsonb_object_agg(coalesce(o.key, n.key), jsonb_build_array(o.value, n.value)), '{}'::jsonb)
    into v_diff
    from jsonb_each(coalesce(v_old, '{}'::jsonb)) o
             full outer join jsonb_each(coalesce(v_new, '{}'::jsonb)) n on o.key = n.key
    where o.value is distinct from n.value;

    if tg_op = 'UPDATE' and v_diff = '{}'::jsonb then
        return null;
    end if;

    v_account_id := coalesce(v_new ->> 'account_id', v_old ->> 'account_id')::uuid;
    v_entity_id := coalesce(v_new ->> 'id', v_old ->> 'id')::uuid;
    if tg_table_name = 'accounts' then
        v_account_id := v_entity_id;
    end if;

    insert into audit_log (account_id, actor_user_id, actor_type, action, entity_type, entity_id, diff, reason, ip)
    values (v_account_id,
            nullif(current_setting('honestrobin.actor_id', true), '')::uuid,
            coalesce(nullif(current_setting('honestrobin.actor_type', true), ''), 'system'),
            tg_table_name || '.' || lower(tg_op),
            tg_table_name,
            v_entity_id,
            v_diff,
            nullif(current_setting('honestrobin.audit_reason', true), ''),
            nullif(current_setting('honestrobin.ip', true), ''));
    return null;
end
$$;

-- Attaches updated_at maintenance, the audit trigger and (for tenant tables) RLS.
create or replace function honestrobin_manage_table(p_table regclass, p_tenant boolean, p_audit boolean default true) returns void
    language plpgsql as
$$
begin
    if exists (select 1
               from information_schema.columns
               where table_schema = 'public' and table_name = p_table::text and column_name = 'updated_at') then
        execute format('create trigger %s_updated_at before update on %s for each row execute function honestrobin_set_updated_at()',
                       p_table::text, p_table);
    end if;
    if p_audit then
        execute format('create trigger %s_audit after insert or update or delete on %s for each row execute function honestrobin_audit()',
                       p_table::text, p_table);
    end if;
    if p_tenant then
        execute format('alter table %s enable row level security', p_table);
        execute format('alter table %s force row level security', p_table);
        execute format('create policy tenant_isolation on %s using (honestrobin_rls_ok(account_id)) with check (honestrobin_rls_ok(account_id))',
                       p_table);
    end if;
end
$$;

alter table audit_log enable row level security;
alter table audit_log force row level security;
create policy audit_read on audit_log for select using (honestrobin_rls_ok(account_id));
create policy audit_append on audit_log for insert with check (true);

-- ---------------------------------------------------------------------------
-- Users (global) and accounts (tenants)
-- ---------------------------------------------------------------------------

create table users
(
    id                uuid primary key     default uuid_generate_v7(),
    email             text        not null,
    name              text        not null,
    password_hash     text,
    locale            text        not null default 'en',
    email_verified_at timestamptz,
    is_instance_admin boolean     not null default false,
    last_login_at     timestamptz,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now()
);
create unique index users_email_uq on users (lower(email));
select honestrobin_manage_table('users', false);

create table accounts
(
    id                    uuid primary key     default uuid_generate_v7(),
    name                  text        not null,
    timezone              text        not null default 'Europe/Berlin',
    week_start            smallint    not null default 1 check (week_start between 1 and 7), -- ISO day, 1 = Monday
    default_currency      char(3)     not null default 'EUR',
    time_rounding_minutes smallint    not null default 0 check (time_rounding_minutes in (0, 6, 15, 30)),
    locale                text        not null default 'en',
    -- fiscal / seller identity (EN 16931)
    legal_name            text,
    address_line1         text,
    address_line2         text,
    postal_code           text,
    city                  text,
    region                text,
    country_code          char(2),
    vat_id                text,
    company_reg_no        text,
    peppol_scheme         text,
    peppol_id             text,
    iban                  text,
    bic                   text,
    -- lifecycle
    status                text        not null default 'active' check (status in ('active', 'lapsed', 'pending_deletion')),
    lapsed_at             timestamptz,
    deletion_requested_at timestamptz,
    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now()
);
-- accounts.id is the tenant key itself
alter table accounts enable row level security;
alter table accounts force row level security;
create policy tenant_isolation on accounts using (honestrobin_rls_ok(id)) with check (honestrobin_rls_ok(id));
select honestrobin_manage_table('accounts', false);

create table memberships
(
    id                      uuid primary key     default uuid_generate_v7(),
    account_id              uuid        not null references accounts (id) on delete cascade,
    user_id                 uuid references users (id) on delete set null,
    -- name/email of the person as known to this account (users may not exist yet for imported or invited people)
    name                    text        not null,
    email                   text        not null,
    role                    text        not null default 'member' check (role in ('admin', 'manager', 'member')),
    status                  text        not null default 'active' check (status in ('pending_invite', 'invited', 'active')),
    is_active               boolean     not null default true,
    is_billable_default     boolean     not null default true,
    default_billable_rate   bigint check (default_billable_rate >= 0),
    cost_rate               bigint check (cost_rate >= 0),
    weekly_capacity_seconds integer     not null default 144000 check (weekly_capacity_seconds >= 0),
    -- configurable permissions (spec §11); admins implicitly have all of them
    can_see_rates           boolean     not null default false,
    can_manage_projects     boolean     not null default false,
    can_manage_invoices     boolean     not null default false,
    created_at              timestamptz not null default now(),
    updated_at              timestamptz not null default now(),
    archived_at             timestamptz
);
create unique index memberships_account_user_uq on memberships (account_id, user_id) where user_id is not null;
create unique index memberships_account_email_uq on memberships (account_id, lower(email));
create index memberships_user_idx on memberships (user_id);
select honestrobin_manage_table('memberships', true);

-- ---------------------------------------------------------------------------
-- Authentication
-- ---------------------------------------------------------------------------

create table user_sessions
(
    id           uuid primary key     default uuid_generate_v7(),
    user_id      uuid        not null references users (id) on delete cascade,
    token_hash   bytea       not null unique,
    ip           text,
    user_agent   text,
    created_at   timestamptz not null default now(),
    last_seen_at timestamptz not null default now(),
    expires_at   timestamptz not null
);
create index user_sessions_user_idx on user_sessions (user_id);
create index user_sessions_expires_idx on user_sessions (expires_at);

-- Personal access tokens for the public API and the browser extension. Bound to one membership.
create table api_tokens
(
    id            uuid primary key     default uuid_generate_v7(),
    account_id    uuid        not null references accounts (id) on delete cascade,
    membership_id uuid        not null references memberships (id) on delete cascade,
    name          text        not null,
    token_hash    bytea       not null unique,
    token_hint    text        not null,
    scopes        text[]      not null default array ['read', 'write'],
    last_used_at  timestamptz,
    expires_at    timestamptz,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);
create index api_tokens_membership_idx on api_tokens (membership_id);
select honestrobin_manage_table('api_tokens', true);

-- Single-use tokens: magic links, password resets, invitations, email verification.
create table login_tokens
(
    id            uuid primary key     default uuid_generate_v7(),
    user_id       uuid references users (id) on delete cascade,
    membership_id uuid references memberships (id) on delete cascade,
    email         text        not null,
    purpose       text        not null check (purpose in ('magic_link', 'password_reset', 'invite', 'email_verify')),
    token_hash    bytea       not null unique,
    expires_at    timestamptz not null,
    used_at       timestamptz,
    created_at    timestamptz not null default now()
);
create index login_tokens_expires_idx on login_tokens (expires_at);

-- ---------------------------------------------------------------------------
-- db-scheduler (background jobs)
-- ---------------------------------------------------------------------------

create table scheduled_tasks
(
    task_name            text                     not null,
    task_instance        text                     not null,
    task_data            bytea,
    execution_time       timestamp with time zone not null,
    picked               boolean                  not null,
    picked_by            text,
    last_success         timestamp with time zone,
    last_failure         timestamp with time zone,
    consecutive_failures int,
    last_heartbeat       timestamp with time zone,
    version              bigint                   not null,
    priority             smallint,
    primary key (task_name, task_instance)
);
create index execution_time_idx on scheduled_tasks (execution_time);
create index last_heartbeat_idx on scheduled_tasks (last_heartbeat);
create index priority_execution_time_idx on scheduled_tasks (priority desc, execution_time asc);

-- ---------------------------------------------------------------------------
-- Grants for the runtime role. audit_log is append-only for the application.
-- ---------------------------------------------------------------------------
do
$$
begin
    if exists (select 1 from pg_roles where rolname = 'honestrobin_app') then
        grant usage on schema public to honestrobin_app;
        grant select, insert, update, delete on all tables in schema public to honestrobin_app;
        revoke update, delete, truncate on audit_log from honestrobin_app;
        grant usage, select on all sequences in schema public to honestrobin_app;
        execute format('alter default privileges for role %I in schema public grant select, insert, update, delete on tables to honestrobin_app', current_user);
        execute format('alter default privileges for role %I in schema public grant usage, select on sequences to honestrobin_app', current_user);
    end if;
exception
    when insufficient_privilege then
        raise notice 'Could not grant privileges to honestrobin_app';
end
$$;
