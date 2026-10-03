-- SPDX-License-Identifier: AGPL-3.0-only
-- When the person last proved who they are in this session (password, sign-in link). Creating API
-- tokens, deleting the account, exporting it and changing where money goes ask for this to be
-- recent, so a session cookie alone is not enough for them.
alter table user_sessions add column authenticated_at timestamptz not null default now();
