-- SPDX-License-Identifier: AGPL-3.0-only
-- Where a device sign-in was asked from (security review, 4 October 2026). The approval page
-- warns when the request came from another network than the browser approving it, as a code
-- someone was talked into typing would. The address goes with the request, a day after it expires.
alter table device_authorizations add column requested_from text;
