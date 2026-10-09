// SPDX-License-Identifier: AGPL-3.0-only
// "Enter as you go": whatever a time entry still lacks (a client, a project, a task) is entered in
// the time dialog, so nobody has to set up the catalog before tracking. The server creates it
// together with the entry, all of it or none of it (POST /api/v1/time_entries/quick).

/** Select value for "add a new one". Radix Select can't hold an empty value. */
export const NEW = "__new__";
