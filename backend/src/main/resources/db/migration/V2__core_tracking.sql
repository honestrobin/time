-- SPDX-License-Identifier: AGPL-3.0-only
-- M1 core tracking: people extras, teams, clients, tasks, projects, assignments, files,
-- time entries, expenses, approvals, budget alerts. Semantics mirror Harvest API v2
-- (bill_by / budget_by values, task and user assignments), verified 2026-09-30.

-- ---------------------------------------------------------------------------
-- Account and people settings
-- ---------------------------------------------------------------------------
alter table accounts
    add column approvals_enabled            boolean not null default false,
    add column timesheet_reminders_enabled  boolean not null default false,
    add column last_timesheet_reminder_week date,
    add column duration_format              text    not null default 'hm' check (duration_format in ('hm', 'decimal'));

alter table memberships
    add column has_access_to_all_future_projects boolean not null default false,
    add column is_contractor                     boolean not null default false,
    add column invited_at                        timestamptz;

create table teams
(
    id          uuid primary key     default uuid_generate_v7(),
    account_id  uuid        not null references accounts (id) on delete cascade,
    name        text        not null,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    archived_at timestamptz
);
create unique index teams_name_uq on teams (account_id, lower(name));
select honestrobin_manage_table('teams', true);

create table team_memberships
(
    id            uuid primary key     default uuid_generate_v7(),
    account_id    uuid        not null references accounts (id) on delete cascade,
    team_id       uuid        not null references teams (id) on delete cascade,
    membership_id uuid        not null references memberships (id) on delete cascade,
    created_at    timestamptz not null default now(),
    unique (team_id, membership_id)
);
select honestrobin_manage_table('team_memberships', true);

-- ---------------------------------------------------------------------------
-- Clients
-- ---------------------------------------------------------------------------
create table clients
(
    id             uuid primary key     default uuid_generate_v7(),
    account_id     uuid        not null references accounts (id) on delete cascade,
    name           text        not null,
    currency       char(3)     not null,
    address_line1  text,
    address_line2  text,
    postal_code    text,
    city           text,
    region         text,
    country_code   char(2),
    vat_id         text,
    company_reg_no text,
    peppol_scheme  text,
    peppol_id      text,
    notes          text,
    is_active      boolean     not null default true,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),
    archived_at    timestamptz
);
create unique index clients_name_uq on clients (account_id, lower(name));
select honestrobin_manage_table('clients', true);

create table client_contacts
(
    id                   uuid primary key     default uuid_generate_v7(),
    account_id           uuid        not null references accounts (id) on delete cascade,
    client_id            uuid        not null references clients (id) on delete cascade,
    name                 text        not null,
    title                text,
    email                text,
    phone                text,
    is_invoice_recipient boolean     not null default false,
    created_at           timestamptz not null default now(),
    updated_at           timestamptz not null default now()
);
create index client_contacts_client_idx on client_contacts (client_id);
select honestrobin_manage_table('client_contacts', true);

-- ---------------------------------------------------------------------------
-- Tasks (global list) and projects
-- ---------------------------------------------------------------------------
create table tasks
(
    id               uuid primary key     default uuid_generate_v7(),
    account_id       uuid        not null references accounts (id) on delete cascade,
    name             text        not null,
    -- added to new projects automatically
    is_default       boolean     not null default false,
    default_billable boolean     not null default true,
    default_rate     bigint check (default_rate >= 0),
    is_active        boolean     not null default true,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    archived_at      timestamptz
);
create unique index tasks_name_uq on tasks (account_id, lower(name));
select honestrobin_manage_table('tasks', true);

create table projects
(
    id                      uuid primary key     default uuid_generate_v7(),
    account_id              uuid        not null references accounts (id) on delete cascade,
    client_id               uuid        not null references clients (id),
    name                    text        not null,
    code                    text,
    is_billable             boolean     not null default true,
    bill_by                 text        not null default 'project' check (bill_by in ('project', 'tasks', 'people', 'none')),
    hourly_rate             bigint check (hourly_rate >= 0),
    -- project: hours per project, project_cost: total project fees, task: hours per task,
    -- task_fees: fees per task, person: hours per person
    budget_by               text        not null default 'none' check (budget_by in ('project', 'project_cost', 'task', 'task_fees', 'person', 'none')),
    budget_is_monthly       boolean     not null default false,
    budget_seconds          bigint check (budget_seconds >= 0),
    budget_amount           bigint check (budget_amount >= 0),
    budget_include_expenses boolean     not null default false,
    budget_alert_percent    numeric(5, 2) check (budget_alert_percent > 0 and budget_alert_percent <= 100),
    notify_when_over_budget boolean     not null default false,
    show_budget_to_all      boolean     not null default false,
    is_fixed_fee            boolean     not null default false,
    fee_amount              bigint check (fee_amount >= 0),
    starts_on               date,
    ends_on                 date,
    notes                   text,
    is_active               boolean     not null default true,
    created_at              timestamptz not null default now(),
    updated_at              timestamptz not null default now(),
    archived_at             timestamptz
);
create index projects_client_idx on projects (client_id);
create unique index projects_name_uq on projects (account_id, client_id, lower(name));
select honestrobin_manage_table('projects', true);

-- Harvest "task assignment"
create table project_tasks
(
    id             uuid primary key     default uuid_generate_v7(),
    account_id     uuid        not null references accounts (id) on delete cascade,
    project_id     uuid        not null references projects (id) on delete cascade,
    task_id        uuid        not null references tasks (id),
    billable       boolean     not null default true,
    hourly_rate    bigint check (hourly_rate >= 0),
    budget_seconds bigint check (budget_seconds >= 0),
    budget_amount  bigint check (budget_amount >= 0),
    is_active      boolean     not null default true,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),
    unique (project_id, task_id)
);
select honestrobin_manage_table('project_tasks', true);

-- Harvest "user assignment"
create table project_members
(
    id                uuid primary key     default uuid_generate_v7(),
    account_id        uuid        not null references accounts (id) on delete cascade,
    project_id        uuid        not null references projects (id) on delete cascade,
    membership_id     uuid        not null references memberships (id) on delete cascade,
    is_manager        boolean     not null default false,
    use_default_rates boolean     not null default true,
    hourly_rate       bigint check (hourly_rate >= 0),
    budget_seconds    bigint check (budget_seconds >= 0),
    is_active         boolean     not null default true,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    unique (project_id, membership_id)
);
create index project_members_membership_idx on project_members (membership_id);
select honestrobin_manage_table('project_members', true);

-- ---------------------------------------------------------------------------
-- Files (receipts, invoice PDFs, exports)
-- ---------------------------------------------------------------------------
create table files
(
    id          uuid primary key     default uuid_generate_v7(),
    account_id  uuid        not null references accounts (id) on delete cascade,
    storage_key text        not null unique,
    filename    text        not null,
    mime        text        not null,
    size        bigint      not null,
    sha256      text        not null,
    uploaded_by uuid references memberships (id) on delete set null,
    created_at  timestamptz not null default now()
);
select honestrobin_manage_table('files', true);

-- ---------------------------------------------------------------------------
-- Time entries
-- ---------------------------------------------------------------------------
create table time_entries
(
    id                     uuid primary key     default uuid_generate_v7(),
    account_id             uuid        not null references accounts (id) on delete cascade,
    membership_id          uuid        not null references memberships (id),
    project_id             uuid        not null references projects (id),
    task_id                uuid        not null references tasks (id),
    -- the calendar day in the account time zone; a timer keeps the day it started on
    spent_date             date        not null,
    -- accumulated seconds, excluding a currently running timer segment
    duration_seconds       integer     not null default 0 check (duration_seconds between 0 and 86400),
    start_time             time,
    end_time               time,
    timer_started_at       timestamptz,
    notes                  text,
    billable               boolean     not null default true,
    billable_rate_snapshot bigint      not null default 0 check (billable_rate_snapshot >= 0),
    cost_rate_snapshot     bigint      not null default 0 check (cost_rate_snapshot >= 0),
    external_source        text,
    external_id            text,
    external_group_id      text,
    external_url           text,
    external_title         text,
    approval_state         text        not null default 'draft' check (approval_state in ('draft', 'submitted', 'approved', 'rejected')),
    invoice_id             uuid,
    is_locked              boolean     not null default false,
    locked_reason          text,
    created_at             timestamptz not null default now(),
    updated_at             timestamptz not null default now()
);
-- at most one running timer per person
create unique index time_entries_one_running_uq on time_entries (membership_id) where timer_started_at is not null;
create index time_entries_member_date_idx on time_entries (account_id, membership_id, spent_date);
create index time_entries_project_date_idx on time_entries (account_id, project_id, spent_date);
create index time_entries_date_idx on time_entries (account_id, spent_date);
create index time_entries_invoice_idx on time_entries (invoice_id) where invoice_id is not null;
select honestrobin_manage_table('time_entries', true);

-- Rows shown on a week timesheet even before any time is entered ("copy last week's rows").
create table timesheet_rows
(
    id              uuid primary key     default uuid_generate_v7(),
    account_id      uuid        not null references accounts (id) on delete cascade,
    membership_id   uuid        not null references memberships (id) on delete cascade,
    week_start_date date        not null,
    project_id      uuid        not null references projects (id) on delete cascade,
    task_id         uuid        not null references tasks (id) on delete cascade,
    created_at      timestamptz not null default now(),
    unique (membership_id, week_start_date, project_id, task_id)
);
select honestrobin_manage_table('timesheet_rows', true, false);

-- ---------------------------------------------------------------------------
-- Expenses
-- ---------------------------------------------------------------------------
create table expense_categories
(
    id          uuid primary key     default uuid_generate_v7(),
    account_id  uuid        not null references accounts (id) on delete cascade,
    name        text        not null,
    -- unit-priced categories (e.g. mileage): amount = units × unit_price
    unit_name   text,
    unit_price  bigint check (unit_price >= 0),
    is_active   boolean     not null default true,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    archived_at timestamptz
);
create unique index expense_categories_name_uq on expense_categories (account_id, lower(name));
select honestrobin_manage_table('expense_categories', true);

create table expenses
(
    id              uuid primary key     default uuid_generate_v7(),
    account_id      uuid          not null references accounts (id) on delete cascade,
    membership_id   uuid          not null references memberships (id),
    project_id      uuid          not null references projects (id),
    category_id     uuid          not null references expense_categories (id),
    spent_date      date          not null,
    amount_minor    bigint        not null check (amount_minor >= 0),
    currency        char(3)       not null,
    units           numeric(12, 2),
    notes           text,
    billable        boolean       not null default true,
    receipt_file_id uuid references files (id) on delete set null,
    approval_state  text          not null default 'draft' check (approval_state in ('draft', 'submitted', 'approved', 'rejected')),
    invoice_id      uuid,
    is_locked       boolean       not null default false,
    locked_reason   text,
    created_at      timestamptz   not null default now(),
    updated_at      timestamptz   not null default now()
);
create index expenses_member_date_idx on expenses (account_id, membership_id, spent_date);
create index expenses_project_date_idx on expenses (account_id, project_id, spent_date);
select honestrobin_manage_table('expenses', true);

-- ---------------------------------------------------------------------------
-- Weekly approvals
-- ---------------------------------------------------------------------------
create table timesheet_submissions
(
    id              uuid primary key     default uuid_generate_v7(),
    account_id      uuid        not null references accounts (id) on delete cascade,
    membership_id   uuid        not null references memberships (id) on delete cascade,
    week_start_date date        not null,
    state           text        not null check (state in ('submitted', 'approved', 'rejected', 'reopened')),
    submitted_at    timestamptz not null default now(),
    decided_by      uuid references memberships (id) on delete set null,
    decided_at      timestamptz,
    comment         text,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    unique (membership_id, week_start_date)
);
select honestrobin_manage_table('timesheet_submissions', true);

-- ---------------------------------------------------------------------------
-- Budget alerts: one row per threshold crossing, removed again if usage drops back below.
-- ---------------------------------------------------------------------------
create table budget_alerts
(
    id           uuid primary key     default uuid_generate_v7(),
    account_id   uuid          not null references accounts (id) on delete cascade,
    project_id   uuid          not null references projects (id) on delete cascade,
    threshold    numeric(5, 2) not null,
    period_start date          not null,
    notified_at  timestamptz   not null default now(),
    unique (project_id, threshold, period_start)
);
select honestrobin_manage_table('budget_alerts', true, false);
