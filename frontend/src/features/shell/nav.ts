// SPDX-License-Identifier: AGPL-3.0-only
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

export function navSections(perms: Perms, edition?: string): NavSectionDef[] {
  const work: NavItem[] = [
    { to: "/", label: "nav.time", key: "t", alsoActive: ["/day/", "/week/"] },
    { to: "/expenses", label: "nav.expenses", key: "e" },
    { to: "/reports", label: "nav.reports", key: "r" },
  ];
  if (perms.isManagerOrAdmin) work.push({ to: "/approvals", label: "nav.approvals", key: "a" });
  if (perms.isAdmin || perms.canManageInvoices) work.push({ to: "/invoices", label: "nav.invoices", key: "i" });
  const sections: NavSectionDef[] = [{ id: "work", items: work }];
  if (perms.isManagerOrAdmin) {
    const manage: NavItem[] = [{ to: "/projects", label: "nav.projects", key: "p" }];
    if (perms.canManageProjects) {
      manage.push({ to: "/clients", label: "nav.clients", key: "c" }, { to: "/tasks", label: "nav.tasks" });
    }
    manage.push({ to: "/team", label: "nav.team", key: "m" });
    sections.push({ id: "manage", heading: "nav.manage", items: manage });
  }
  const settings: NavItem[] = [{ to: "/settings/profile", label: "nav.profile" }, { to: "/developers", label: "nav.developers" }];
  if (perms.isAdmin) {
    settings.unshift(
      { to: "/settings/account", label: "nav.account" },
      { to: "/settings/invoices", label: "nav.invoiceSettings" },
      { to: "/settings/payments", label: "nav.payments" },
      { to: "/settings/accounting", label: "nav.accounting" },
      { to: "/settings/expense-categories", label: "nav.expenseCategories" },
    );
    // The Honest Robin Cloud subscription; self-hosted instances have nothing to pay for.
    if (edition === "cloud") settings.splice(1, 0, { to: "/settings/billing", label: "nav.billing" });
    settings.push({ to: "/settings/import", label: "nav.import" }, { to: "/settings/audit", label: "nav.auditLog" });
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
