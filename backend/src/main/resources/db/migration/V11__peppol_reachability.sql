-- SPDX-License-Identifier: AGPL-3.0-only
-- Whether a client can receive e-invoices over Peppol (spec §7.3: "Reachable via Peppol"),
-- as last checked with the provider.
alter table clients
    add column peppol_reachable  boolean,
    add column peppol_checked_at timestamptz;
