-- SPDX-License-Identifier: AGPL-3.0-only
-- Deleting an account also deletes people who belonged to no other account. Their sign-up and
-- sign-in history in the audit log (email, name, IP address) has no account id, so the account's
-- purge didn't reach it. This removes it, only in the system context, as honestrobin_purge_audit does.
create or replace function honestrobin_purge_user_audit(p_user_ids uuid[]) returns bigint
    language plpgsql
    security definer
    set search_path = public as
$$
declare
    v_count bigint;
begin
    if coalesce(current_setting('honestrobin.rls_bypass', true), '') <> 'on' then
        raise exception 'honestrobin_purge_user_audit runs only in the system context';
    end if;
    delete from audit_log where account_id is null and entity_type = 'users' and entity_id = any (p_user_ids);
    get diagnostics v_count = row_count;
    return v_count;
end
$$;
revoke all on function honestrobin_purge_user_audit(uuid[]) from public;

do
$$
begin
    if exists (select 1 from pg_roles where rolname = 'honestrobin_app') then
        grant execute on function honestrobin_purge_user_audit(uuid[]) to honestrobin_app;
    end if;
end
$$;
