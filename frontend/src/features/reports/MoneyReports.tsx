// SPDX-License-Identifier: AGPL-3.0-only
// The uninvoiced, budget and expense reports.
import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { Fragment } from "react";
import { useTranslation } from "react-i18next";
import { Checkbox, EmptyState, LoadingRow } from "../../design";
import { api, errorInfo, unwrap } from "../../lib/api";
import { formatDate, formatDuration, formatMoney, type DurationStyle } from "../../lib/format";
import { budgetFigures, Meter } from "../projects/BudgetMeter";
import type { Range } from "./period";
import { EXPENSE_GROUPS, filterQuery, type ExpenseGroup, type ReportSearch } from "./search";
import { Amounts } from "./TimeReport";

interface Common {
  search: ReportSearch;
  range: Range;
  durationStyle: DurationStyle;
  canSeeRates: boolean;
}

/** Billable work and expenses not invoiced yet, client by client, ready to bill. */
export function UninvoicedReport({ search, range, durationStyle, canSeeRates, canInvoice }: Common & { canInvoice: boolean }) {
  const { t } = useTranslation();
  const query = { from: range.from, to: range.to, client_id: search.client, project_id: search.project };
  const report = useQuery({
    queryKey: ["reports", "uninvoiced", query],
    queryFn: () => unwrap(api.GET("/api/v1/reports/uninvoiced", { params: { query } })),
    placeholderData: (prev) => prev,
  });
  const data = report.data;
  const cols = 3 + (canSeeRates ? 3 : 0) + (canInvoice ? 1 : 0);
  return (
    <section aria-label={t("reports.tabs.uninvoiced")}>
      {data && data.totals.length > 0 && (
        <dl className="report-summary">
          {data.totals.map((x) => (
            <div key={x.currency}>
              <dt>{t("reports.uninvoiced.waiting", { currency: x.currency })}</dt>
              <dd className="report-money">{canSeeRates ? formatMoney(x.amount + x.expense_amount, x.currency) : formatDuration(x.seconds, durationStyle)}</dd>
              {canSeeRates && <dd className="muted small">{t("reports.uninvoiced.split", { hours: formatDuration(x.seconds, durationStyle), expenses: formatMoney(x.expense_amount, x.currency) })}</dd>}
            </div>
          ))}
        </dl>
      )}
      <p className="muted small report-line">{t("reports.uninvoiced.lead")}</p>
      {report.error ? (
        <p className="notice notice-error">{errorInfo(report.error).message}</p>
      ) : data && data.clients.length === 0 ? (
        <EmptyState title={t("reports.uninvoiced.empty")} body={t("reports.uninvoiced.emptyBody")} />
      ) : (
        <div className="table-scroll">
          <table className="ledger report-table">
            <thead>
              <tr>
                <th>{t("reports.col.clientProject")}</th>
                <th className="num">{t("reports.col.hours")}</th>
                {canSeeRates && <th className="num">{t("reports.col.amount")}</th>}
                {canSeeRates && <th className="num">{t("reports.col.expenses")}</th>}
                {canSeeRates && <th className="num">{t("reports.col.total")}</th>}
                <th>{t("reports.col.oldest")}</th>
                {canInvoice && <th />}
              </tr>
            </thead>
            <tbody>
              {report.isLoading && <LoadingRow colSpan={cols} />}
              {data?.clients.map((c) => (
                <Fragment key={c.client.id}>
                  <tr className="group-row">
                    <td>
                      <strong>{c.client.name}</strong> <span className="muted small">{c.currency}</span>
                    </td>
                    <td className="num">{formatDuration(c.seconds, durationStyle)}</td>
                    {canSeeRates && <td className="num">{formatMoney(c.amount, c.currency)}</td>}
                    {canSeeRates && <td className="num">{formatMoney(c.expense_amount, c.currency)}</td>}
                    {canSeeRates && (
                      <td className="num">
                        <strong>{formatMoney(c.amount + c.expense_amount, c.currency)}</strong>
                      </td>
                    )}
                    <td className="nowrap">{c.oldest_date ? formatDate(c.oldest_date, "short") : "—"}</td>
                    {canInvoice && (
                      <td className="num">
                        <Link to="/invoices/new" search={{ client: c.client.id, from: range.from, to: range.to }} className="btn btn-secondary btn-sm">
                          {t("reports.uninvoiced.createInvoice")}
                        </Link>
                      </td>
                    )}
                  </tr>
                  {c.projects.length > 1 &&
                    c.projects.map((p) => (
                      <tr key={p.project.id} className="sub-row">
                        <td>{p.project.name}</td>
                        <td className="num">{formatDuration(p.seconds, durationStyle)}</td>
                        {canSeeRates && <td className="num">{formatMoney(p.amount, c.currency)}</td>}
                        {canSeeRates && <td className="num">{formatMoney(p.expense_amount, c.currency)}</td>}
                        {canSeeRates && <td className="num">{formatMoney(p.amount + p.expense_amount, c.currency)}</td>}
                        <td className="nowrap muted">{p.oldest_date ? formatDate(p.oldest_date, "short") : "—"}</td>
                        {canInvoice && <td />}
                      </tr>
                    ))}
                  {c.projects.length === 1 && (
                    <tr className="sub-row">
                      <td colSpan={cols} className="muted small">
                        {c.projects[0].project.name}
                      </td>
                    </tr>
                  )}
                </Fragment>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

/** Budget, spent and remaining for each project with a budget; over-budget projects stand out. */
export function BudgetReport({ search, durationStyle, onSearch }: Common & { onSearch: (patch: Partial<ReportSearch>) => void }) {
  const { t } = useTranslation();
  const query = { client_id: search.client, include_archived: search.archived ?? false };
  const report = useQuery({
    queryKey: ["reports", "budget", query],
    queryFn: () => unwrap(api.GET("/api/v1/reports/budget", { params: { query } })),
    placeholderData: (prev) => prev,
  });
  const rows = report.data?.rows ?? [];
  const over = rows.filter((r) => r.budget.is_over_budget).length;
  return (
    <section aria-label={t("reports.tabs.budget")}>
      <div className="report-controls">
        {over > 0 && <span className="badge badge-signal">{t("reports.budget.overCount", { count: over })}</span>}
        <Checkbox checked={search.archived ?? false} onChange={(v) => onSearch({ archived: v || undefined })} label={t("reports.budget.includeArchived")} />
      </div>
      {report.error ? (
        <p className="notice notice-error">{errorInfo(report.error).message}</p>
      ) : !report.isLoading && rows.length === 0 ? (
        <EmptyState title={t("reports.budget.empty")} body={t("reports.budget.emptyBody")} />
      ) : (
        <div className="table-scroll">
          <table className="ledger report-table">
            <thead>
              <tr>
                <th>{t("reports.col.project")}</th>
                <th>{t("reports.col.budgetBy")}</th>
                <th className="num">{t("reports.col.budget")}</th>
                <th className="num">{t("reports.col.spent")}</th>
                <th className="num">{t("reports.col.remaining")}</th>
                <th className="col-meter">{t("reports.col.used")}</th>
              </tr>
            </thead>
            <tbody>
              {report.isLoading && <LoadingRow colSpan={6} />}
              {rows.map((r) => {
                const f = budgetFigures(r.budget, durationStyle);
                return (
                  <tr key={r.project.id} className={f.over ? "is-over" : undefined}>
                    <td>
                      <Link to="/projects/$projectId" params={{ projectId: r.project.id }} className="row-link">
                        {r.project.name}
                      </Link>
                      <div className="muted small">{r.client.name}</div>
                    </td>
                    <td className="small">
                      {t(`reports.budgetBy.${r.budget.budget_by}`)}
                      {r.budget.is_monthly && <div className="muted">{t("reports.budget.monthly")}</div>}
                    </td>
                    <td className="num">{f.canShow ? f.budget || "—" : "—"}</td>
                    <td className="num">{f.canShow ? f.spent : "—"}</td>
                    <td className={f.over ? "num over" : "num"}>{f.canShow && f.hasBudget ? (f.over ? t("reports.budget.over", { amount: f.remaining }) : f.remaining) : "—"}</td>
                    <td className="col-meter">{f.canShow && <Meter spent={f.spentValue} budget={f.budgetValue} />}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

/** Expenses by category, client, project or person; amounts per currency. */
export function ExpenseReport({ search, range, onSearch }: Common & { onSearch: (patch: Partial<ReportSearch>) => void }) {
  const { t } = useTranslation();
  const group: ExpenseGroup = (EXPENSE_GROUPS as readonly string[]).includes(search.group ?? "") ? (search.group as ExpenseGroup) : "category";
  const query = { ...filterQuery(search, range), task_id: undefined, group_by: group };
  const report = useQuery({
    queryKey: ["reports", "expenses", query],
    queryFn: () => unwrap(api.GET("/api/v1/reports/expenses", { params: { query } })),
    placeholderData: (prev) => prev,
  });
  const data = report.data;
  return (
    <section aria-label={t("reports.tabs.expenses")}>
      {data && data.amounts.length > 0 && (
        <dl className="report-summary">
          <div>
            <dt>{t("reports.expenses.total", { count: data.count })}</dt>
            <dd className="report-money">
              <Amounts amounts={data.amounts} field="amount" />
            </dd>
          </div>
          <div>
            <dt>{t("reports.expenses.billable")}</dt>
            <dd className="report-money">
              <Amounts amounts={data.amounts} field="billable_amount" />
            </dd>
          </div>
          <div>
            <dt>{t("reports.expenses.uninvoiced")}</dt>
            <dd className="report-money">
              <Amounts amounts={data.amounts} field="uninvoiced_amount" />
            </dd>
          </div>
        </dl>
      )}
      <div className="report-controls">
        <span className="muted small">{t("reports.groupBy")}</span>
        <div className="seg seg-sm" role="group" aria-label={t("reports.groupBy")}>
          {EXPENSE_GROUPS.map((g) => (
            <button key={g} type="button" aria-pressed={group === g} onClick={() => onSearch({ group: g })}>
              {t(`reports.groups.${g}`)}
            </button>
          ))}
        </div>
      </div>
      {report.error ? (
        <p className="notice notice-error">{errorInfo(report.error).message}</p>
      ) : data && data.rows.length === 0 ? (
        <EmptyState title={t("reports.expenses.empty")} body={t("reports.empty.body")} />
      ) : (
        <div className="table-scroll">
          <table className="ledger report-table">
            <thead>
              <tr>
                <th>{t(`reports.groups.${group}`)}</th>
                <th className="num">{t("reports.col.count")}</th>
                <th className="num">{t("reports.col.amount")}</th>
                <th className="num">{t("reports.col.billable")}</th>
                <th className="num">{t("reports.col.uninvoiced")}</th>
              </tr>
            </thead>
            <tbody>
              {report.isLoading && <LoadingRow colSpan={5} />}
              {data?.rows.map((r) => (
                <tr key={r.id}>
                  <td>
                    {r.name}
                    {r.client_name && <div className="muted small">{r.client_name}</div>}
                  </td>
                  <td className="num">{r.count}</td>
                  <td className="num">
                    <Amounts amounts={r.amounts} field="amount" />
                  </td>
                  <td className="num">
                    <Amounts amounts={r.amounts} field="billable_amount" />
                  </td>
                  <td className="num">
                    <Amounts amounts={r.amounts} field="uninvoiced_amount" />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
