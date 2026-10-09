// SPDX-License-Identifier: AGPL-3.0-only
// The menu shows the core, and each switched-off feature comes back only when it's switched on.
import { describe, expect, it } from "vitest";
import { FEATURES } from "../../lib/features";
import { navSections, shortcutList } from "./nav";

const ADMIN: Parameters<typeof navSections>[0] = {
  role: "admin",
  isAdmin: true,
  isManager: false,
  isManagerOrAdmin: true,
  canSeeRates: true,
  canManageProjects: true,
  canManageInvoices: true,
};

const pages = (edition: string, features: readonly string[]) => navSections(ADMIN, edition, features).flatMap((s) => s.items.map((i) => i.to));

describe("the menu", () => {
  it("shows only the core by default, in both editions", () => {
    expect(pages("selfhost", [])).toEqual(["/", "/reports", "/projects", "/clients", "/settings/account", "/settings/profile"]);
    expect(pages("cloud", [])).toEqual(["/", "/reports", "/projects", "/clients", "/settings/account", "/settings/billing", "/settings/profile"]);
  });

  it("brings back each feature that's switched on, and only that one", () => {
    const all = pages("selfhost", FEATURES);
    for (const page of [
      "/expenses",
      "/approvals",
      "/invoices",
      "/tasks",
      "/team",
      "/settings/invoices",
      "/settings/payments",
      "/settings/accounting",
      "/settings/expense-categories",
      "/settings/import",
    ]) {
      expect(all).toContain(page);
    }
    expect(pages("selfhost", ["invoices"])).toEqual(["/", "/reports", "/invoices", "/projects", "/clients", "/settings/account", "/settings/invoices", "/settings/profile"]);
  });

  it("offers shortcuts only to pages that are shown", () => {
    const keys = shortcutList(navSections(ADMIN, "selfhost", [])).map((s) => s.key);
    expect(keys).toEqual(expect.arrayContaining(["T", "R", "P", "C", "N", "S"]));
    for (const hidden of ["E", "A", "I", "M"]) expect(keys).not.toContain(hidden);
  });
});
