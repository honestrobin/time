// SPDX-License-Identifier: AGPL-3.0-only
import { useNavigate, useSearch } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";
import { PageHeader, Tabs } from "../../design";
import { featureOn, useFeatures } from "../../lib/features";
import { usePermissions } from "../../lib/session";
import { useAccountSettings } from "../time/hooks";
import { DetailedReport } from "./DetailedReport";
import { BudgetReport, ExpenseReport, UninvoicedReport } from "./MoneyReports";
import { useFilterOptions } from "./options";
import { periodFor, type Range, type Unit } from "./period";
import { filterQuery, toQueryString, type FilterKey, type ReportSearch, type Tab } from "./search";
import { TimeReport } from "./TimeReport";
import { ExportMenu, Filters, PeriodPicker } from "./Toolbar";
import "./reports.css";

/** Which filters each report understands. */
const FILTERS_FOR: Record<Tab, FilterKey[]> = {
  time: ["client", "project", "task", "person", "team"],
  detailed: ["client", "project", "task", "person", "team"],
  uninvoiced: ["client", "project"],
  budget: ["client"],
  expenses: ["client", "project", "person", "team", "category"],
};

const ALL_TABS: Tab[] = ["time", "detailed", "uninvoiced", "budget", "expenses"];

/** Reports (spec §12): what was worked, what's billable, what's waiting to be invoiced, how budgets stand. */
export function ReportsPage() {
  const { t } = useTranslation();
  const search = useSearch({ from: "/app/reports" });
  const navigate = useNavigate({ from: "/reports" });
  const perms = usePermissions();
  const account = useAccountSettings();
  const { options, projects, available: offered } = useFilterOptions();
  const features = useFeatures();
  // Reports and filters of switched-off features stay out of sight (lib/features).
  const shown: Record<Tab, boolean> = {
    time: true,
    detailed: true,
    uninvoiced: perms.isManagerOrAdmin && featureOn(features, "invoices"),
    budget: featureOn(features, "budgets"),
    expenses: featureOn(features, "expenses"),
  };
  const hiddenFilters: FilterKey[] = [
    ...(featureOn(features, "tasks") ? [] : (["task"] as const)),
    ...(featureOn(features, "team") ? [] : (["person", "team"] as const)),
  ];
  const available = offered.filter((k) => !hiddenFilters.includes(k));

  const tabs: Tab[] = ALL_TABS.filter((x) => shown[x]);
  const tab: Tab = search.tab && tabs.includes(search.tab) ? search.tab : "time";
  const unit: Unit = search.unit ?? "month";
  const range: Range =
    unit === "all" ? {} : search.from && search.to ? { from: search.from, to: search.to } : periodFor(unit === "custom" ? "month" : unit, account.today, account.weekStart);

  const update = (patch: Partial<ReportSearch>) => void navigate({ search: (prev: ReportSearch) => ({ ...prev, ...patch }), replace: false });
  const filterKeys = available.filter((k) => FILTERS_FOR[tab].includes(k)).concat(tab === "expenses" ? ["category"] : []);
  const hasPeriod = tab !== "budget";
  const durationStyle = account.durationStyle;
  const canInvoice = perms.isAdmin || perms.canManageInvoices;

  const base = { ...filterQuery(search, range) };
  const exportPath = (kind: string, format: "csv" | "xlsx", extra: Record<string, string | boolean | undefined> = {}) =>
    `/api/v1/reports/${kind}/export?${toQueryString({ ...base, ...extra, format })}`;
  const exports = (() => {
    switch (tab) {
      case "time":
        return [
          { label: t("reports.export.csv"), path: exportPath("time", "csv", { group_by: search.group ?? "project" }) },
          { label: t("reports.export.xlsx"), path: exportPath("time", "xlsx", { group_by: search.group ?? "project" }) },
          { label: t("reports.export.detailedCsv"), path: exportPath("detailed", "csv") },
          { label: t("reports.export.detailedXlsx"), path: exportPath("detailed", "xlsx") },
        ];
      case "expenses":
        return [
          { label: t("reports.export.csv"), path: exportPath("expenses", "csv", { group_by: search.group ?? "category" }) },
          { label: t("reports.export.xlsx"), path: exportPath("expenses", "xlsx", { group_by: search.group ?? "category" }) },
          { label: t("reports.export.itemsCsv"), path: exportPath("expense_items", "csv") },
          { label: t("reports.export.itemsXlsx"), path: exportPath("expense_items", "xlsx") },
        ];
      case "budget":
        return (["csv", "xlsx"] as const).map((f) => ({
          label: t(`reports.export.${f}`),
          path: `/api/v1/reports/budget/export?${toQueryString({ client_id: search.client, include_archived: search.archived, format: f })}`,
        }));
      default:
        return (["csv", "xlsx"] as const).map((f) => ({ label: t(`reports.export.${f}`), path: exportPath(tab, f) }));
    }
  })();

  return (
    <div className="page page-wide">
      <PageHeader title={t("reports.title")} actions={<ExportMenu exports={exports} />} />
      <Tabs
        value={tab}
        onChange={(v) => update({ tab: v as Tab, group: undefined })}
        tabs={tabs.map((x) => ({ value: x, label: t(`reports.tabs.${x}`) }))}
      />
      <div className="report-toolbar">
        {hasPeriod && (
          <PeriodPicker
            unit={unit}
            range={range}
            today={account.today}
            weekStart={account.weekStart}
            onChange={(u, r) => update({ unit: u, from: r.from, to: r.to })}
          />
        )}
        <Filters search={search} available={filterKeys} options={options} showBillable={tab === "time" || tab === "detailed" || tab === "expenses"} onChange={update} />
      </div>

      {tab === "time" && <TimeReport search={search} range={range} durationStyle={durationStyle} canSeeRates={perms.canSeeRates} onSearch={update} />}
      {tab === "detailed" && <DetailedReport search={search} range={range} durationStyle={durationStyle} canSeeRates={perms.canSeeRates} projects={projects} />}
      {tab === "uninvoiced" && <UninvoicedReport search={search} range={range} durationStyle={durationStyle} canSeeRates={perms.canSeeRates} canInvoice={canInvoice} />}
      {tab === "budget" && <BudgetReport search={search} range={range} durationStyle={durationStyle} canSeeRates={perms.canSeeRates} onSearch={update} />}
      {tab === "expenses" && <ExpenseReport search={search} range={range} durationStyle={durationStyle} canSeeRates={perms.canSeeRates} onSearch={update} />}
    </div>
  );
}
