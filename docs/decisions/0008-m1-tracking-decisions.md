# 0008. Core tracking decisions (M1)

- Status: accepted
- Date: 2026-09-30
- Spec reference: §4, §5.1–5.4, §11, AT-1.5

## Verified against Harvest API v2 (2026-09-30)

- `bill_by`: Project, Tasks, People, none. `budget_by`: project (hours), project_cost (total fees), task (hours per task), task_fees (fees per task), person (hours per person), none. Projects also have `budget_is_monthly` and `cost_budget_include_expenses`. We mirror all of these.
- User assignments carry `use_default_rates` + `hourly_rate`; task assignments carry `billable` + `hourly_rate` + `budget`. Rate precedence (§5.1) is implemented once, as SQL (`RateSql`), and used both for snapshots and for "update rates on N entries".
- Time entries accumulate across timer restarts (`hours_without_timer`), which we model as `duration_seconds` plus `timer_started_at`.
- A timer left running past midnight stays on the day it started, and can still be stopped.
- Rate limits: 100 requests / 15 s general, 100 requests / 15 **min** for the Reports API (relevant to the M2 verification screen).

## Decisions

1. **Expense amounts are visible to whoever can see the expense.** They are the person's own spend, not a billing rate. Billable/cost rates, billable and cost amounts, budgets in money, fees and category unit prices are hidden without rate visibility. AT-1.5 is enforced centrally with Jackson views (`@JsonView(Views.Rates)`), and `RateVisibilityTest` crawls every GET endpoint as a member.
2. **Budgets and durations are stored in seconds** (`budget_seconds`), not decimal hours, following the §3.5 duration convention. The API speaks seconds; the UI shows hours.
3. **Start/end times are `start_time`/`end_time` (`time` type)** rather than `started_at`/`ended_at`. They are times of day on `spent_date`, like Harvest's `started_time`/`ended_time`.
4. **Timers run only on today's date** (account time zone). Starting an entry from an earlier day continues it as a new entry today.
5. **Approvals are decided per project.** A manager approving a submitted week approves only the entries on projects they manage. The week becomes `approved` once nothing is left pending. Admins can reopen a week, giving a reason that is stored on every changed row's audit record and as a `timesheet.reopen` event.
6. **Rate history is not modelled.** Harvest keeps dated billable/cost rates per user; we snapshot rates on every entry instead, so history is preserved where it matters (imported entries keep Harvest's snapshot).
7. **Budget alerts are evaluated synchronously** in the transaction that changes time or expenses. Each crossing is recorded in `budget_alerts`, and dropping back below a threshold re-arms it.
8. **PATCH distinguishes absent from null** (`Patch<T>`), so rates and budgets can be cleared by sending `null`.
9. **Optimistic concurrency**: single resources carry a weak `ETag` from `updated_at`. `If-Match` is honoured on PATCH/DELETE; without it, the last write wins.
