# Harvest test data

`harvest_fill.py` fills a Harvest account with made-up data through Harvest's API v2, so Time's
Harvest import can be tried against a real account. Use it only on a Harvest trial made for this:
made-up details, nothing from real work. It needs Python 3 and nothing else.

## What it creates

These are the numbers from a run on 5 October 2026. The number of time entries changes a little
with the date.

- **6 clients in 5 currencies** (two in EUR, one each in GBP, USD, CAD and JPY), with 9 contacts.
  Addresses are made up and emails use `example.com`.
- **6 tasks** (one archived), **5 expense categories** (one counted in km, one archived) and
  **1 role** holding the account owner.
- **10 projects**, covering every way Harvest bills:
  - by task rate (3, one of them in yen)
  - by person rate (2: one with its own rate, one with the person's default rate)
  - by project rate (3)
  - not billable (1)
  - fixed fee (1)

  They also cover every kind of budget Harvest has: hours in total (one monthly), fees in total,
  hours per task, fees per task, hours per person, and none. Harvest has no fees-per-person budget.
  There are 33 task assignments, some with their own rate, and one user assignment per project.
  One project is archived once it's invoiced.
- **About 220 time entries** for the account owner on weekdays, over about three and a half
  months. The oldest are more than 90 days old, so both of the import's phases run. Some entries
  aren't billable, some have no notes, and a few carry a link to an issue tracker.
- **16 expenses** in 4 categories. Some aren't billable, two are counted in km, and two have a PDF
  receipt attached.
- **10 invoices** in 5 currencies:
  - 8 are built from tracked time and expenses, so those entries are marked billed. The other 2
    are free-form.
  - All have a tax, 2 have a second tax and 2 have a discount.
  - 5 are paid (one in two payments), 1 is partly paid, 2 are sent and unpaid (at least one overdue),
    1 is written off and 1 is a draft. There are 7 payments in all.
- **3 estimates**: one accepted, one declined, one draft. Time keeps them as read-only documents.

**What it doesn't fill:**

- **Other people.** A trial invites each new person by email, so only the account owner's time is
  filled.
- **Timesheet approval.** Harvest's API has no way to submit or approve time, so every entry stays
  unsubmitted.
- **A running timer**, unless you add `--with-running-timer`. Time imports a running timer as
  stopped. Its hours keep growing until you stop it, so that project's figures drift.

**Email:** it sends none. Invoices and estimates are marked as sent without being sent, and
payments are recorded without a thank-you email.

## Run it

1. Start a Harvest 30-day trial with made-up details.
2. Make a personal access token at <https://id.getharvest.com/developers>. Note the token and the
   Account ID shown with it. The token stays on your machine: never in a chat, a file or a
   commit.
3. From the root of this repository:

   ```sh
   export HARVEST_ACCOUNT_ID=1234567                              # the Account ID from step 2
   read -rs HARVEST_ACCESS_TOKEN && export HARVEST_ACCESS_TOKEN   # paste the token, then Enter (it isn't shown)
   python3 scripts/harvest-fill/harvest_fill.py --dry-run         # lists what it would create; sends nothing
   python3 scripts/harvest-fill/harvest_fill.py
   ```

It shows the account's name and asks before it creates anything. A run sends about 400 requests.
Harvest allows 100 every 15 seconds, so it takes a few minutes.

At the end it prints the figures Time's import is checked against, read back from Harvest the way
Time's verification screen reads them. For each client and each currency, it gives hours,
billable hours, billable amount, the number of invoices and their total. It saves the same
figures to a JSON file in `/tmp`, together with each project's figures per month and every
invoice. Run `--totals` to print them again later.

Then import into Time from the Import page in Settings (`/settings/import`), with the same
token, and compare.

## Remove it

```sh
python3 scripts/harvest-fill/harvest_fill.py --delete
```

It finds the clients, tasks, expense categories and roles whose names end in " (test)", together
with everything under those clients. It lists what it found and asks before deleting anything.
It deletes payments and invoices first, then estimates. Next come the projects, which takes their
time entries and expenses with them, then contacts and clients. Last go the tasks, categories and
the role. The script won't fill an account that already holds test data, so run `--delete` before
you fill it again.

## Options

| Option | What it does |
|---|---|
| `--dry-run` | Prints what it would create and the totals it expects. Works offline and needs no token. |
| `--delete` | Removes what an earlier run created. |
| `--totals` | Prints and saves Harvest's totals for the test data. |
| `--yes` | Doesn't ask first. |
| `--seed N` | Gives different time entries (default 2026). |
| `--with-running-timer` | Also leaves a timer running today. |
| `--totals-file PATH` | Saves the totals somewhere else. Keep it out of the repository. |
| `--verbose` | In a dry run, lists every time entry too. |

## Not yet checked against a live account

Endpoints and fields were checked against Harvest's API v2 docs on 5 October 2026. The script
hasn't run against a real Harvest account yet. Lines marked `VERIFY` in the code are what the
docs don't settle:

- which `bill_by` a fixed-fee project takes (the script sends `Tasks`, with task rates)
- whether Harvest assigns default tasks or the project's creator to a new project (the script
  checks and updates whatever is already there)
- whether lines imported into an invoice come taxed (the script makes sure they are)
- whether Harvest takes the discount off before the tax (this only affects the dry run's
  estimates; a live run reads Harvest's amounts)
- the form fields for uploading a receipt
- whether `external_reference` is accepted from a personal token (if it's refused, the script
  carries on without it)
- the time format for start and end times, used only when the account tracks time that way
- whether an archived project can be deleted as it is (the script unarchives it first)
