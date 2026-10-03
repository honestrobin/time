-- SPDX-License-Identifier: AGPL-3.0-only
-- M3: invoice defaults, sending, reminders and the public invoice page.

-- Invoice defaults per account. Payment details are free text so they fit any country's
-- banking (IBAN and BIC, ACH routing numbers, UPI, ...); see decision record 0009.
alter table accounts
    add column invoice_payment_terms_days integer  not null default 30 check (invoice_payment_terms_days between 0 and 365),
    add column invoice_notes              text,
    add column invoice_footer             text,
    add column payment_instructions       text,
    add column default_tax1_name          text,
    add column default_tax1_percent       numeric(7, 4) check (default_tax1_percent between 0 and 100),
    add column default_tax2_name          text,
    add column default_tax2_percent       numeric(7, 4) check (default_tax2_percent between 0 and 100),
    add column invoice_reminders_enabled  boolean  not null default false,
    -- days relative to the due date: -3 is three days before, 7 is a week after
    add column invoice_reminder_days      integer[] not null default '{-3,0,7}',
    add column invoice_bcc_sender         boolean  not null default false;

alter table invoices
    -- the unguessable part of the public invoice link
    add column public_token          text unique,
    add column payment_instructions  text,
    add column footer                text,
    add column sent_to               text[],
    add column reminders_sent        integer[] not null default '{}',
    add column last_reminder_at      timestamptz,
    -- how lines were built from tracked time: project, task, person, detailed, or null for free-form
    add column grouping              text check (grouping in ('project', 'task', 'person', 'detailed')),
    add column view_count            integer   not null default 0,
    add column last_viewed_at        timestamptz;

alter table invoices
    add constraint invoices_amounts_ck check (total_minor >= 0 and paid_minor >= 0 and subtotal_minor >= 0 and discount_minor >= 0);

alter table invoice_lines
    add column task_id       uuid references tasks (id) on delete set null,
    add column membership_id uuid references memberships (id) on delete set null;

create index invoices_due_idx on invoices (account_id, due_date) where state in ('sent', 'partially_paid');
