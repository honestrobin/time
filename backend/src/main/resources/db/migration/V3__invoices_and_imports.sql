-- SPDX-License-Identifier: AGPL-3.0-only
-- M2: invoices (imported now, created natively in M3), import jobs and idempotency links.

-- Harvest rounds either up to or to the nearest 6/15/30 minutes (verified 2026-10-01).
alter table accounts
    add column time_rounding_mode text not null default 'up' check (time_rounding_mode in ('up', 'nearest'));

-- ---------------------------------------------------------------------------
-- Invoices
-- ---------------------------------------------------------------------------
create table invoice_sequences
(
    id          uuid primary key     default uuid_generate_v7(),
    account_id  uuid        not null references accounts (id) on delete cascade,
    name        text        not null,
    prefix      text        not null default '',
    next_number bigint      not null default 1 check (next_number > 0),
    padding     smallint    not null default 0 check (padding between 0 and 12),
    -- {YYYY} in the prefix is replaced by the issue year; the counter restarts each year
    per_year    boolean     not null default false,
    current_year smallint,
    is_default  boolean     not null default false,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);
create unique index invoice_sequences_default_uq on invoice_sequences (account_id) where is_default;
select honestrobin_manage_table('invoice_sequences', true);

create table invoices
(
    id                 uuid primary key     default uuid_generate_v7(),
    account_id         uuid        not null references accounts (id) on delete cascade,
    client_id          uuid        not null references clients (id),
    -- null for drafts: numbers are assigned on send, never reused
    number             text,
    sequence_id        uuid references invoice_sequences (id),
    issue_date         date        not null,
    due_date           date,
    currency           char(3)     not null,
    state              text        not null default 'draft' check (state in ('draft', 'open', 'sent', 'partially_paid', 'paid', 'void')),
    subject            text,
    notes              text,
    purchase_order     text,
    buyer_reference    text,
    -- two named taxes (Harvest compatibility) or EU VAT mode with per-line categories
    tax1_name          text,
    tax1_percent       numeric(7, 4),
    tax2_name          text,
    tax2_percent       numeric(7, 4),
    vat_mode           text check (vat_mode in ('standard', 'reverse_charge', 'exempt', 'outside_scope')),
    exemption_reason   text,
    discount_percent   numeric(7, 4),
    subtotal_minor     bigint      not null default 0,
    discount_minor     bigint      not null default 0,
    tax1_minor         bigint      not null default 0,
    tax2_minor         bigint      not null default 0,
    total_minor        bigint      not null default 0,
    paid_minor         bigint      not null default 0,
    due_minor          bigint      not null default 0,
    period_start       date,
    period_end         date,
    payment_terms      text,
    payment_terms_days integer,
    einvoice_status    text,
    source             text        not null default 'native' check (source in ('native', 'harvest_import')),
    is_read_only       boolean     not null default false,
    sent_at            timestamptz,
    paid_at            timestamptz,
    voided_at          timestamptz,
    created_by         uuid references memberships (id) on delete set null,
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now()
);
create unique index invoices_number_uq on invoices (account_id, number) where number is not null;
create index invoices_client_idx on invoices (account_id, client_id, issue_date);
create index invoices_state_idx on invoices (account_id, state);
select honestrobin_manage_table('invoices', true);

create table invoice_lines
(
    id                uuid primary key     default uuid_generate_v7(),
    account_id        uuid           not null references accounts (id) on delete cascade,
    invoice_id        uuid           not null references invoices (id) on delete cascade,
    position          integer        not null default 0,
    kind              text           not null default 'service' check (kind in ('service', 'product', 'expense')),
    -- the item category as named by the source (e.g. Harvest's "Service")
    kind_label        text,
    project_id        uuid references projects (id) on delete set null,
    description       text,
    quantity          numeric(14, 4) not null default 1,
    unit_price_minor  bigint         not null default 0,
    tax1_applies      boolean        not null default false,
    tax2_applies      boolean        not null default false,
    vat_category_code text,
    vat_percent       numeric(7, 4),
    line_total_minor  bigint         not null default 0,
    created_at        timestamptz    not null default now(),
    updated_at        timestamptz    not null default now()
);
create index invoice_lines_invoice_idx on invoice_lines (invoice_id);
select honestrobin_manage_table('invoice_lines', true);

create table invoice_time_links
(
    id              uuid primary key     default uuid_generate_v7(),
    account_id      uuid        not null references accounts (id) on delete cascade,
    invoice_line_id uuid        not null references invoice_lines (id) on delete cascade,
    time_entry_id   uuid        not null unique references time_entries (id) on delete cascade,
    created_at      timestamptz not null default now()
);
select honestrobin_manage_table('invoice_time_links', true, false);

create table invoice_expense_links
(
    id              uuid primary key     default uuid_generate_v7(),
    account_id      uuid        not null references accounts (id) on delete cascade,
    invoice_line_id uuid        not null references invoice_lines (id) on delete cascade,
    expense_id      uuid        not null unique references expenses (id) on delete cascade,
    created_at      timestamptz not null default now()
);
select honestrobin_manage_table('invoice_expense_links', true, false);

create table payments
(
    id           uuid primary key     default uuid_generate_v7(),
    account_id   uuid        not null references accounts (id) on delete cascade,
    invoice_id   uuid        not null references invoices (id) on delete cascade,
    amount_minor bigint      not null,
    paid_at      timestamptz,
    paid_date    date        not null,
    method       text        not null default 'manual' check (method in ('manual', 'stripe', 'other')),
    external_id  text,
    notes        text,
    recorded_by  text,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now()
);
create index payments_invoice_idx on payments (invoice_id);
select honestrobin_manage_table('payments', true);

alter table time_entries
    add constraint time_entries_invoice_fk foreign key (invoice_id) references invoices (id) on delete set null;
alter table expenses
    add constraint expenses_invoice_fk foreign key (invoice_id) references invoices (id) on delete set null;

-- Read-only archive of documents we don't model (e.g. Harvest estimates).
create table archived_documents
(
    id          uuid primary key     default uuid_generate_v7(),
    account_id  uuid        not null references accounts (id) on delete cascade,
    kind        text        not null,
    external_id text        not null,
    number      text,
    issue_date  date,
    client_id   uuid references clients (id) on delete set null,
    data        jsonb       not null,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    unique (account_id, kind, external_id)
);
select honestrobin_manage_table('archived_documents', true, false);

-- ---------------------------------------------------------------------------
-- Imports and external identities
-- ---------------------------------------------------------------------------

-- Idempotency for imports and syncs: one row per (system, entity, external id).
create table external_links
(
    id          uuid primary key     default uuid_generate_v7(),
    account_id  uuid        not null references accounts (id) on delete cascade,
    system      text        not null check (system in ('harvest', 'qbo', 'xero', 'storecove')),
    entity_type text        not null,
    external_id text        not null,
    entity_id   uuid        not null,
    -- source data needed later (e.g. an entry's invoice id before that invoice is imported)
    meta        jsonb,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    unique (account_id, system, entity_type, external_id)
);
create index external_links_entity_idx on external_links (entity_type, entity_id);
select honestrobin_manage_table('external_links', true, false);

create table import_jobs
(
    id                 uuid primary key     default uuid_generate_v7(),
    account_id         uuid        not null references accounts (id) on delete cascade,
    source             text        not null default 'harvest' check (source in ('harvest')),
    mode               text        not null check (mode in ('api', 'csv')),
    -- awaiting_account: OAuth done, user picks the Harvest account; preview: CSV mapping
    status             text        not null default 'queued' check (status in ('awaiting_account', 'preview', 'queued', 'running', 'failed', 'completed', 'syncing', 'cancelled')),
    phase              text,
    progress           jsonb       not null default '{}'::jsonb,
    started_by         uuid references memberships (id) on delete set null,
    external_account_id text,
    token_encrypted    text,
    refresh_token_encrypted text,
    token_expires_at   timestamptz,
    sync_until         timestamptz,
    last_synced_at     timestamptz,
    stats              jsonb       not null default '{}'::jsonb,
    verification       jsonb,
    options            jsonb       not null default '{}'::jsonb,
    error              text,
    started_at         timestamptz,
    finished_at        timestamptz,
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now()
);
select honestrobin_manage_table('import_jobs', true);

-- Records that could not be imported, with the reason; retried on request.
create table import_issues
(
    id          uuid primary key     default uuid_generate_v7(),
    account_id  uuid        not null references accounts (id) on delete cascade,
    job_id      uuid        not null references import_jobs (id) on delete cascade,
    severity    text        not null default 'error' check (severity in ('error', 'warning', 'info')),
    entity_type text        not null,
    external_id text,
    reason      text        not null,
    payload     jsonb,
    resolved_at timestamptz,
    created_at  timestamptz not null default now()
);
create index import_issues_job_idx on import_issues (job_id);
select honestrobin_manage_table('import_issues', true, false);

-- CSV uploads waiting to be imported (kept until the job ends).
create table import_files
(
    id          uuid primary key     default uuid_generate_v7(),
    account_id  uuid        not null references accounts (id) on delete cascade,
    job_id      uuid        not null references import_jobs (id) on delete cascade,
    file_id     uuid        not null references files (id) on delete cascade,
    kind        text,
    mapping     jsonb       not null default '{}'::jsonb,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);
select honestrobin_manage_table('import_files', true, false);
