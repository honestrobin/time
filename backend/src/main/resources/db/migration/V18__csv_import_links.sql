-- SPDX-License-Identifier: AGPL-3.0-only
-- Rows imported from Harvest's CSV exports (spec §6.6) are keyed by a hash of what they say, kept
-- apart from the ids the Harvest API gives.
alter table external_links drop constraint external_links_system_check;
alter table external_links add constraint external_links_system_check
    check (system in ('harvest', 'harvest-csv', 'qbo', 'xero', 'storecove'));
