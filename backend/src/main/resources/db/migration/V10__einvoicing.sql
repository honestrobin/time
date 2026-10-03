-- SPDX-License-Identifier: AGPL-3.0-only
-- E-invoicing (spec §7, M5).

alter table accounts
    -- Factur-X/ZUGFeRD hybrid PDFs: null follows the default (on where the account's country is
    -- in the EU), true and false are the admin's choice.
    add column einvoice_hybrid_pdf  boolean,
    -- The seller contact on e-invoices (XRechnung requires a name, phone and email).
    add column invoice_contact_name  text,
    add column invoice_contact_email text,
    add column invoice_contact_phone text;

-- Sending e-invoices through a Peppol access point (Storecove first), one row per attempt.
create table einvoice_transmissions
(
    id           uuid primary key     default uuid_generate_v7(),
    account_id   uuid        not null references accounts (id) on delete cascade,
    invoice_id   uuid        not null references invoices (id) on delete cascade,
    provider     text        not null,
    format       text        not null check (format in ('facturx', 'xrechnung', 'peppol')),
    status       text        not null default 'queued' check (status in ('queued', 'sent', 'delivered', 'failed', 'rejected')),
    provider_ref text,
    last_error   text,
    attempts     integer     not null default 0,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now()
);
create index einvoice_transmissions_invoice_idx on einvoice_transmissions (invoice_id);
select honestrobin_manage_table('einvoice_transmissions', true);
