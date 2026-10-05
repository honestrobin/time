# Account export format

An account export is one zip file. Admins create it under **Settings → Account → Move out**
(`POST /api/v1/account/move_out`, or `POST /api/v1/exports` for the zip alone). It works on every
plan and in both editions, also while an account is lapsed or waiting to be deleted, because the
data belongs to the customer (spec §13). Moving out changes nothing else in the account.

The same zip imports into any Honest Robin instance, cloud or self-hosted, under
**Settings → Account → Import an export** (`POST /api/v1/accounts/import`, raw zip body).

## Layout

```
manifest.json          format, version, checksums (see below)
README.txt             what's inside, where you can go next, and what was still connected to
                       the account when the export was made, with how to end each one
data/<table>.json      the complete record: one file per table
csv/*.csv              spreadsheets for people (time entries, expenses, invoices, clients,
                       contacts, projects, tasks, people)
invoices/*.pdf         every issued invoice as its client gets it: a Factur-X hybrid PDF where
                       the account sends those
invoices/e-invoices/   each invoice as XRechnung (`…-xrechnung.xml`) and Peppol BIS
                       (`…-peppol.xml`), where the invoice has what that format needs
files/<id>/<filename>  receipts and other uploads; <id> is the row in data/files.json
```

`data/` is the source of truth. The CSVs, PDFs and e-invoice files are a convenience, and the
importer ignores them. The CSVs are UTF-8 with a header row; dates are `YYYY-MM-DD`, hours are
decimal and amounts are plain numbers.

## manifest.json

| Field | Meaning |
|---|---|
| `format` | Always `honestrobin-export`. |
| `version` | Format version, currently `1`. Raised only for changes an older importer can't read. Importers accept every version up to their own. |
| `exported_at` | When the snapshot was taken (UTC). Every file shows this same moment: the export reads in one repeatable-read transaction. |
| `app_version` | The Honest Robin version that made the export. The format is the same in both editions. |
| `account_id`, `account_name` | The account. |
| `tables` | For each data file, in import order: `name`, `rows` and `sha256` of the file's bytes. |
| `files` | For each uploaded file: `id`, `path` in the zip, `size`, `sha256`. |
| `excluded` | Tables left out on purpose, with the reason (see below). |

## Data files

Each `data/<table>.json` is a JSON array with one row object per line, ordered by primary key,
with the table's columns as keys in table order. Values:

| Database type | JSON |
|---|---|
| uuid, text | string |
| smallint, integer, bigint | number |
| numeric | number, scale kept (`25.0000`) |
| boolean | `true` / `false` |
| date | `"2026-10-03"` |
| time | `"09:30:00"` |
| timestamptz | ISO 8601 in UTC, e.g. `"2026-10-03T11:35:56.123456Z"` |
| jsonb | the JSON value itself |
| arrays | JSON arrays |
| bytea | base64 string |
| NULL | `null` |

Money is always integer **minor units** with the currency next to it (`total_minor` with
`currency`), and durations are integer **seconds**, as everywhere in Honest Robin.

The output is deterministic: the same data exports to the same bytes, so the checksums of two
exports can be compared table by table. The round-trip test (`AccountDataTest`, AT-6.1) relies on
this.

### Tables, in import order

`accounts`, `users`, `memberships`, `teams`, `team_memberships`, `clients`, `client_contacts`,
`tasks`, `projects`, `project_tasks`, `project_members`, `files`, `expense_categories`,
`invoice_sequences`, `invoices`, `invoice_lines`, `time_entries`, `timesheet_rows`,
`timesheet_submissions`, `expenses`, `invoice_time_links`, `invoice_expense_links`, `payments`, `einvoice_transmissions`,
`archived_documents`, `external_links`, `budget_alerts`, `account_milestones`, `audit_log`.

Columns that are left out:

- `accounts`: `status`, `lapsed_at`, `deletion_requested_at`. Whether an account is lapsed or
  being deleted belongs to the instance, not to the data; an imported account starts active.
- `users`: `password_hash`, `is_instance_admin`, `last_login_at`, and the two-factor columns
  (`totp_secret_encrypted`, `totp_pending_encrypted`, `totp_enabled_at`, `totp_last_step`). Only
  people who are members of the account are exported, and nothing that signs them in. An account
  that requires two-factor sign-in (`accounts.require_two_factor`) still does after importing,
  so whoever imports it sets up two-factor sign-in first.

### Left out on purpose

| Table | Why |
|---|---|
| `api_tokens` | Secrets; create new tokens after importing. |
| `integrations` | Stripe and other connections are encrypted with the old instance's key; connect again after importing. |
| `import_jobs`, `import_issues`, `import_files` | A log of that instance's Harvest imports, not account data. Their uploaded files are still in `files`. |
| `account_exports` | Earlier exports. |
| `user_sessions`, `login_tokens` | Sign-ins. |
| `user_recovery_codes` | Two-factor recovery codes; two-factor sign-in is set up again on the new instance. |
| `rate_limit_events` | Rate-limit counters of that instance. |
| `device_authorizations` | Sign-ins of devices such as the browser extension; sign them in again. |
| `accounting_sync_items` | The queue of pushes to QuickBooks or Xero; connect again after importing. What was already pushed stays in `external_links`. |
| `subscriptions`, `billing_events` | An Honest Robin Cloud subscription belongs to that instance; subscribe again where you import. |

A test (`every table is either exported or left out on purpose`) fails when a new table is added
to the schema without deciding which list it belongs to.

## Importing

The importer:

1. Checks `format` and `version`, and the SHA-256 of every data file against the manifest. A
   changed or damaged file stops the import before anything is written.
2. Refuses an account id that already exists on the instance (`409 account_exists`): importing
   the same export twice is a mistake, not a merge. An account that was deleted on this instance
   comes back under a new id, so anything outside that still names the old one, like a
   subscription, can't find it; importing it a second time is refused the same way.
3. Requires every row to belong to the account in the manifest, and refuses a row that points
   at a row of another account, even where a foreign key would allow it. The zip comes from a
   user and is written with row-level security off, so it is checked as untrusted input.
4. Writes everything in one transaction, keeping every row's id, so the links between rows stay
   as they were. Audit triggers are paused: the exported `audit_log` already holds the history,
   and one `accounts.imported` entry is added.
5. Links nobody to a sign-in except the person importing, who becomes an admin. Everyone else
   keeps their place, history and rates in the account but comes over as "not invited yet", so
   no one is added to an account on a new instance without saying yes: invite them again from
   Team. (Audit entries keep what happened but not who, for the same reason.)
6. Gives every file a storage key on this instance and puts the files back into storage (and
   removes them again if the import fails).
7. Keeps each invoice's public link, unless the link belonged to an account deleted on this
   instance: those never work again, so such an invoice gets a new link. Clients may still have
   the old link in their inbox, and it must never show anything else.

## Deletion

An admin can delete an account under **Settings → Account → Delete this account**, by typing its
name. The account turns read-only for 14 days (exports keep working), every admin gets an email,
and any admin can cancel. After 14 days a job deletes the account's rows, files, exports and audit
log for good, plus users left without any account. One audit entry (`accounts.purged`, with the
account id and nothing else) records that it happened, and a SHA-256 of each invoice's public link
is kept, so those links never work again.

Deleting also ends what the account holds outside: Honest Robin's access to its Stripe account
(unless another workspace on the instance still uses it), QuickBooks and Xero books, and its sender
and Peppol ID at Storecove; on Honest Robin Cloud, its subscription is cancelled. A provider that
doesn't answer doesn't stop the deletion; the server's log says what to remove by hand.

Backups still hold deleted data until they rotate out. For Honest Robin Cloud the planned
retention for nightly backups and point-in-time recovery is 30 days (to be confirmed before
launch), so deleted data would be gone from backups 30 days after the deletion. Self-hosters set
their own retention; see `docs/self-host.md`.
