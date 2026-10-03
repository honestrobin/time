-- SPDX-License-Identifier: AGPL-3.0-only
-- Two-factor sign-in with an authenticator app (TOTP), one-time recovery codes, and accounts that
-- require it of everyone.

alter table users
    add column totp_secret_encrypted  text,        -- SecretBox; set once the first code confirmed it
    add column totp_pending_encrypted text,        -- during setup, until then
    add column totp_enabled_at        timestamptz,
    add column totp_last_step         bigint;      -- the time step of the last code used, so each works once

create table user_recovery_codes
(
    id         uuid primary key     default uuid_generate_v7(),
    user_id    uuid        not null references users (id) on delete cascade,
    code_hash  bytea       not null,
    used_at    timestamptz,
    created_at timestamptz not null default now()
);
create index user_recovery_codes_user_idx on user_recovery_codes (user_id);

alter table accounts add column require_two_factor boolean not null default false;

-- After the password (or a sign-in link), a short-lived challenge waits for the code.
alter table login_tokens drop constraint login_tokens_purpose_check;
alter table login_tokens add constraint login_tokens_purpose_check
    check (purpose in ('magic_link', 'password_reset', 'invite', 'email_verify', 'two_factor'));

-- The audit log never holds secrets, and a code being used is not a change worth a row.
create or replace function honestrobin_audit() returns trigger
    language plpgsql as
$$
declare
    v_old        jsonb;
    v_new        jsonb;
    v_diff       jsonb;
    v_account_id uuid;
    v_entity_id  uuid;
    v_excluded   text[] := array ['updated_at', 'last_used_at', 'last_login_at', 'password_hash', 'token_hash', 'credentials_encrypted', 'token_encrypted', 'totp_secret_encrypted', 'totp_pending_encrypted', 'totp_last_step'];
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
