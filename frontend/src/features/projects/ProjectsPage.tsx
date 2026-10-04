// SPDX-License-Identifier: AGPL-3.0-only
import { useQueries, useQuery } from "@tanstack/react-query";
import { Link, useNavigate } from "@tanstack/react-router";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { EmptyState, LoadingRow, PageHeader } from "../../design";
import { errorInfo } from "../../lib/api";
import { formatDuration, formatMoney } from "../../lib/format";
import { usePermissions } from "../../lib/session";
import { BudgetMeter, budgetFigures } from "./BudgetMeter";
import { isLineBudget, isMoneyBudget } from "./labels";
import { budgetQuery, byName, projectsQuery, useAccountDefaults, type Project } from "./queries";
import "./catalog.css";

type Filter = "active" | "archived";

/** Projects grouped under their client, like a ledger with a heading per account. */
export function ProjectsPage() {
  const { t } = useTranslation();
  const perms = usePermissions();
  const navigate = useNavigate();
  const { durationStyle } = useAccountDefaults();
  const [filter, setFilter] = useState<Filter>("active");
  const list = useQuery(projectsQuery(filter === "active"));
  const projects = list.data?.data ?? [];

  // Usage per budgeted project: the list endpoint has no spent figures.
  const budgeted = projects.filter((p) => p.budget_by !== "none");
  const budgets = useQueries({ queries: budgeted.map((p) => budgetQuery(p.id)) });
  const budgetById = new Map(budgeted.map((p, i) => [p.id, budgets[i]?.data]));

  const groups = groupByClient(projects);
  const cols = 4;

  const newButton = perms.canManageProjects && (
    <Link to="/projects/new" className="btn btn-primary">
      {t("projects.newProject")}
    </Link>
  );

  const billing = (p: Project) => {
    if (!p.is_billable) return <span className="muted">{t("projects.billBy.none")}</span>;
    const label = t(`projects.billByShort.${p.bill_by}`);
    if (p.bill_by === "project" && perms.canSeeRates && p.hourly_rate != null) {
      return t("projects.list.rateAt", { label, rate: formatMoney(p.hourly_rate, p.client.currency) });
    }
    return label;
  };

  const budgetCell = (p: Project) => {
    if (p.budget_by === "none") return <span className="muted">{t("projects.list.noBudget")}</span>;
    const usage = budgetById.get(p.id);
    if (usage) {
      const f = budgetFigures(usage, durationStyle);
      if (f.hasBudget && f.canShow) {
        return (
          <div className="list-budget">
            <span className="num" style={{ textAlign: "left" }}>
              {t("projects.list.spentOf", { spent: f.spent, budget: f.budget })}
              {usage.is_monthly && <span className="muted"> {t("projects.list.perMonth")}</span>}
            </span>
            <BudgetMeter usage={usage} alertPercent={p.budget_alert_percent} compact />
          </div>
        );
      }
    }
    // Still loading, a per-line budget with no lines set yet, or a money budget the caller can't see.
    if (isLineBudget(p.budget_by)) return <span className="muted">{t(`projects.budgetBy.${p.budget_by}`)}</span>;
    if (isMoneyBudget(p.budget_by)) return p.budget_amount != null ? formatMoney(p.budget_amount, p.client.currency) : <span className="muted">{t("projects.budgetBy.project_cost")}</span>;
    return p.budget_seconds != null ? t("projects.list.hours", { hours: formatDuration(p.budget_seconds, durationStyle) }) : null;
  };

  return (
    <div className="page">
      <PageHeader
        title={t("projects.title")}
        lead={t("projects.lead")}
        actions={
          <>
            <div className="seg" role="group" aria-label={t("projects.list.filter")}>
              <button type="button" aria-pressed={filter === "active"} onClick={() => setFilter("active")}>
                {t("projects.list.active")}
              </button>
              <button type="button" aria-pressed={filter === "archived"} onClick={() => setFilter("archived")}>
                {t("projects.list.archived")}
              </button>
            </div>
            {newButton}
          </>
        }
      />

      {list.isError ? (
        <p className="notice notice-error" role="alert">
          {errorInfo(list.error).message}
        </p>
      ) : !list.isLoading && projects.length === 0 ? (
        filter === "active" ? (
          <EmptyState
            robin="notes"
            title={t("projects.empty.title")}
            body={perms.canManageProjects ? t("projects.empty.body") : t("projects.empty.bodyManager")}
            action={
              perms.canManageProjects && (
                <div className="row">
                  <Link to="/projects/new" className="btn btn-primary">
                    {t("projects.empty.action")}
                  </Link>
                  {perms.isAdmin && (
                    <Link to="/settings/import" className="btn">
                      {t("projects.empty.import")}
                    </Link>
                  )}
                </div>
              )
            }
          />
        ) : (
          <EmptyState title={t("projects.empty.archivedTitle")} body={t("projects.empty.archivedBody")} />
        )
      ) : (
        <table className="ledger">
          <thead>
            <tr>
              <th>{t("projects.list.project")}</th>
              <th className="col-narrow">{t("projects.fields.code")}</th>
              <th>{t("projects.list.billing")}</th>
              <th>{t("projects.list.budget")}</th>
            </tr>
          </thead>
          <tbody>
            {list.isLoading && <LoadingRow colSpan={cols} />}
            {groups.map(({ client, projects: rows }) => [
              <tr key={`c-${client.id}`} className="group-row">
                <th colSpan={cols} scope="colgroup">
                  {client.name}
                </th>
              </tr>,
              ...rows.map((p) => (
                <tr key={p.id} className="is-clickable" onClick={() => void navigate({ to: "/projects/$projectId", params: { projectId: p.id } })}>
                  <td>
                    <Link to="/projects/$projectId" params={{ projectId: p.id }} className="row-link" onClick={(e) => e.stopPropagation()}>
                      {p.name}
                    </Link>
                  </td>
                  <td className="code">{p.code ?? ""}</td>
                  <td>
                    {billing(p)}
                    {p.is_fixed_fee && <span className="badge" style={{ marginLeft: 8 }}>{t("projects.list.fixedFee")}</span>}
                  </td>
                  <td>{budgetCell(p)}</td>
                </tr>
              )),
            ])}
          </tbody>
        </table>
      )}
    </div>
  );
}

function groupByClient(projects: Project[]) {
  const map = new Map<string, { client: Project["client"]; projects: Project[] }>();
  for (const p of projects) {
    const g = map.get(p.client.id) ?? { client: p.client, projects: [] };
    g.projects.push(p);
    map.set(p.client.id, g);
  }
  const groups = [...map.values()].sort((a, b) => byName(a.client, b.client));
  for (const g of groups) g.projects.sort(byName);
  return groups;
}
