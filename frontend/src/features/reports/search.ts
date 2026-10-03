// SPDX-License-Identifier: AGPL-3.0-only
// A report's state lives in the URL, so a report can be bookmarked, shared and stepped back through.
import { UNITS, type Range, type Unit } from "./period";

export const TABS = ["time", "detailed", "uninvoiced", "budget", "expenses"] as const;
export type Tab = (typeof TABS)[number];

export const TIME_GROUPS = ["client", "project", "task", "person"] as const;
export type TimeGroup = (typeof TIME_GROUPS)[number];
export const EXPENSE_GROUPS = ["category", "client", "project", "person"] as const;
export type ExpenseGroup = (typeof EXPENSE_GROUPS)[number];

export const FILTER_KEYS = ["client", "project", "task", "person", "team", "category"] as const;
export type FilterKey = (typeof FILTER_KEYS)[number];

export interface ReportSearch {
  tab?: Tab;
  unit?: Unit;
  from?: string;
  to?: string;
  group?: string;
  client?: string[];
  project?: string[];
  task?: string[];
  person?: string[];
  team?: string[];
  category?: string[];
  billable?: "yes" | "no";
  archived?: boolean;
}

const DATE = /^\d{4}-\d{2}-\d{2}$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function oneOf<T extends string>(values: readonly T[], v: unknown): T | undefined {
  return typeof v === "string" && (values as readonly string[]).includes(v) ? (v as T) : undefined;
}

function ids(v: unknown): string[] | undefined {
  const list = (Array.isArray(v) ? v : typeof v === "string" ? v.split(",") : []).filter((x): x is string => typeof x === "string" && UUID.test(x));
  return list.length ? list : undefined;
}

/** Keeps only well-formed values: a hand-edited or stale link degrades to defaults, never to an error. */
export function validateReportSearch(s: Record<string, unknown>): ReportSearch {
  const out: ReportSearch = {
    tab: oneOf(TABS, s.tab),
    unit: oneOf(UNITS, s.unit),
    from: typeof s.from === "string" && DATE.test(s.from) ? s.from : undefined,
    to: typeof s.to === "string" && DATE.test(s.to) ? s.to : undefined,
    group: oneOf([...TIME_GROUPS, ...EXPENSE_GROUPS], s.group),
    billable: oneOf(["yes", "no"] as const, s.billable),
    archived: s.archived === true || s.archived === "true" ? true : undefined,
  };
  for (const k of FILTER_KEYS) out[k] = ids(s[k]);
  // Drop undefined keys so links stay short.
  return Object.fromEntries(Object.entries(out).filter(([, v]) => v !== undefined)) as ReportSearch;
}

/** The API's filter parameters for a report. */
export function filterQuery(s: ReportSearch, range: Range) {
  return {
    from: range.from,
    to: range.to,
    client_id: s.client,
    project_id: s.project,
    task_id: s.task,
    membership_id: s.person,
    team_id: s.team,
    category_id: s.category,
    billable: s.billable === undefined ? undefined : s.billable === "yes",
  };
}

/** The same parameters as a query string, for export links (lists repeat their key). */
export function toQueryString(params: Record<string, string | number | boolean | string[] | undefined>): string {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined) continue;
    if (Array.isArray(v)) v.forEach((x) => q.append(k, x));
    else q.append(k, String(v));
  }
  return q.toString();
}

export function activeFilterCount(s: ReportSearch): number {
  return FILTER_KEYS.reduce((n, k) => n + (s[k]?.length ? 1 : 0), 0) + (s.billable ? 1 : 0);
}
