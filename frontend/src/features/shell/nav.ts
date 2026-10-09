// SPDX-License-Identifier: AGPL-3.0-only
import { featureOn, type Feature } from "../../lib/features";
import type { usePermissions } from "../../lib/session";

type Perms = ReturnType<typeof usePermissions>;

export interface NavItem {
  to: string;
  label: string;
  /** Single-key shortcut that jumps to the page. */
  key?: string;
  /** Addresses under other paths that belong to the same page, e.g. Time's day and week views. */
  alsoActive?: string[];
}

export interface NavSectionDef {
  id: string;
  heading?: string;
  items: NavItem[];
}

/**
 * Pages outside the menu, reached from Settings and Profile: the audit log and the API reference
 * come with every plan, so they stay one step away without crowding the menu.
 */
export const OUT_OF_MENU = ["/settings/audit", "/developers"];

/**
 * The menu. The core is always there: Time, Reports, Projects and Clients, Account and Profile.
 * Everything else appears only when its feature is switched on.
 */
export function navSections(perms: Perms, edition?: string, features: readonly string[] = []): NavSectionDef[] {
  const on = (f: Feature) => featureOn(features, f);
  const work: NavItem[] = [{ to: "/", label: "nav.time", key: "t", alsoActive: ["/day/", "/week/"] }];
  if (on("expenses")) work.push({ to: "/expenses", label: "nav.expenses", key: "e" });
  work.push({ to: "/reports", label: "nav.reports", key: "r" });
  if (on("approvals") && perms.isManagerOrAdmin) work.push({ to: "/approvals", label: "nav.approvals", key: "a" });
  if (on("invoices") && (perms.isAdmin || perms.canManageInvoices)) work.push({ to: "/invoices", label: "nav.invoices", key: "i" });
  const sections: NavSectionDef[] = [{ id: "work", items: work }];
  if (perms.isManagerOrAdmin) {
    const manage: NavItem[] = [{ to: "/projects", label: "nav.projects", key: "p" }];
    if (perms.canManageProjects) {
      manage.push({ to: "/clients", label: "nav.clients", key: "c" });
      if (on("tasks")) manage.push({ to: "/tasks", label: "nav.tasks" });
    }
    if (on("team")) manage.push({ to: "/team", label: "nav.team", key: "m" });
    sections.push({ id: "manage", heading: "nav.manage", items: manage });
  }
  const settings: NavItem[] = [{ to: "/settings/profile", label: "nav.profile" }];
  if (perms.isAdmin) {
    const admin: NavItem[] = [{ to: "/settings/account", label: "nav.account" }];
    // The Honest Robin Cloud subscription; self-hosted instances have nothing to pay for.
    if (edition === "cloud") admin.push({ to: "/settings/billing", label: "nav.billing" });
    if (on("invoices")) admin.push({ to: "/settings/invoices", label: "nav.invoiceSettings" });
    if (on("payments")) admin.push({ to: "/settings/payments", label: "nav.payments" });
    if (on("accounting")) admin.push({ to: "/settings/accounting", label: "nav.accounting" });
    if (on("expenses")) admin.push({ to: "/settings/expense-categories", label: "nav.expenseCategories" });
    if (on("import")) admin.push({ to: "/settings/import", label: "nav.import" });
    settings.unshift(...admin);
  }
  sections.push({ id: "settings", heading: "nav.settings", items: settings });
  return sections;
}

export function shortcutList(sections: NavSectionDef[]) {
  return [
    ...sections.flatMap((s) => s.items).filter((i) => i.key).map((i) => ({ key: i.key!.toUpperCase(), label: i.label })),
    { key: "N", label: "time.shortcuts.newEntry" },
    { key: "S", label: "time.shortcuts.stop" },
    { key: "←", label: "time.shortcuts.prev" },
    { key: "→", label: "time.shortcuts.next" },
    { key: "D", label: "time.shortcuts.dayView" },
    { key: "W", label: "time.shortcuts.weekView" },
    { key: "?", label: "shortcuts.help" },
  ];
}
