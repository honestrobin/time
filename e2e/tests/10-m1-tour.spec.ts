// SPDX-License-Identifier: AGPL-3.0-only
import { expect, test, type Page } from "@playwright/test";
import { call, freshWorkspace, snap, uniqueEmail } from "./helpers";

/**
 * Walks every M1 page with a small, realistic data set and checks that no page shows a raw
 * translation key. With SCREENSHOTS=1 it also saves each page for a design review.
 * Signs up a new workspace where sign-up is open; otherwise the admin from 00-first-user creates one.
 */

const iso = (d: Date) => d.toISOString().slice(0, 10);

/** Monday of the current week, in UTC, so the seeded entries land in the week the app opens on. */
function monday(): Date {
  const d = new Date();
  d.setUTCHours(12, 0, 0, 0);
  d.setUTCDate(d.getUTCDate() - ((d.getUTCDay() + 6) % 7));
  return d;
}

/** A key that was never translated renders as itself, e.g. "projects.fields.name". */
async function expectNoRawKeys(page: Page, where: string) {
  const text = await page.locator("body").innerText();
  const raw = text.match(/\b(?:app|nav|auth|settings|time|team|approvals|expenses|categories|projects|clients|tasks|invoices|reports|moveOut)\.[a-z][A-Za-z]*(?:\.[A-Za-z_]+)+\b/g);
  expect(raw, `untranslated keys on ${where}`).toBeNull();
}

async function visit(page: Page, path: string, name: string, ready: () => Promise<unknown>) {
  await page.goto(path);
  await ready();
  await expectNoRawKeys(page, name);
  await snap(page, name);
}

test("M1 pages render with data and without raw translation keys", async ({ page, request }) => {
  test.setTimeout(180_000);
  const config = await (await request.get("/api/v1/auth/config")).json();

  // A fresh workspace, so the tour never depends on what is already in the database.
  await freshWorkspace(page, request, "Tour & Co");

  const me = await call(page, "GET", "/api/v1/me");
  const account: string = me.current_membership_id;
  expect(account, "the signed-in user has a membership").toBeTruthy();

  const client = await call(page, "POST", "/api/v1/clients", {
    name: "Northwind Studio",
    currency: "EUR",
    address_line1: "Hafenstraße 12",
    postal_code: "20457",
    city: "Hamburg",
    country_code: "DE",
    vat_id: "DE123456789",
  });
  await call(page, "POST", `/api/v1/clients/${client.id}/contacts`, { name: "Jonas Becker", title: "Finance manager", email: "jonas@northwind.test", is_invoice_recipient: true });
  // A second client on another continent and currency, so nothing quietly assumes euros.
  const kiwi = await call(page, "POST", "/api/v1/clients", { name: "Kiwi Outdoor Ltd", currency: "NZD", country_code: "NZ" });

  const design = await call(page, "POST", "/api/v1/tasks", { name: "Design", default_billable: true, is_default: true, default_rate: 9000 });
  const dev = await call(page, "POST", "/api/v1/tasks", { name: "Development", default_billable: true, is_default: true, default_rate: 11000 });
  const meetings = await call(page, "POST", "/api/v1/tasks", { name: "Meetings", default_billable: false, is_default: false });

  const ivo = await call(page, "POST", "/api/v1/people", { name: "Ivo Designer", email: uniqueEmail("ivo"), role: "member", send_invite: false, default_billable_rate: 8000, cost_rate: 4500 });
  const ana = await call(page, "POST", "/api/v1/people", { name: "Ana Manager", email: uniqueEmail("ana"), role: "manager", send_invite: false, default_billable_rate: 12000 });
  await call(page, "POST", "/api/v1/teams", { name: "Design", membership_ids: [ivo.id] });

  const relaunch = await call(page, "POST", "/api/v1/projects", {
    client_id: client.id,
    name: "Website relaunch",
    code: "NW-01",
    is_billable: true,
    bill_by: "project",
    hourly_rate: 9500,
    budget_by: "project",
    budget_seconds: 40 * 3600,
    budget_alert_percent: 80,
    notify_when_over_budget: true,
    show_budget_to_all: true,
    task_ids: [design.id, dev.id, meetings.id],
    membership_ids: [account, ivo.id, ana.id],
    notes: "Relaunch of northwind.test with a new booking flow.",
  });
  await call(page, "POST", "/api/v1/projects", {
    client_id: client.id,
    name: "Support retainer",
    is_billable: true,
    bill_by: "people",
    budget_by: "project_cost",
    budget_amount: 250000,
    budget_is_monthly: true,
    task_ids: [dev.id, meetings.id],
    membership_ids: [account, ana.id],
  });

  const mon = monday();
  const day = (offset: number) => {
    const d = new Date(mon);
    d.setUTCDate(d.getUTCDate() + offset);
    return iso(d);
  };
  const today = iso(new Date());
  const entries: [number, string, number, string][] = [
    [0, design.id, 3.5, "Homepage wireframes"],
    [0, meetings.id, 1, "Kickoff call"],
    [1, design.id, 6, "Booking flow, first pass"],
    [2, dev.id, 7.25, "Booking form and validation"],
    [3, dev.id, 5, "Payment step"],
  ];
  for (const [offset, taskId, hours, notes] of entries) {
    if (day(offset) > today) continue;
    await call(page, "POST", "/api/v1/time_entries", { project_id: relaunch.id, task_id: taskId, spent_date: day(offset), duration_seconds: Math.round(hours * 3600), notes });
  }

  // Work for the New Zealand client, by Ivo, so reports show two currencies and two people.
  const trailMaps = await call(page, "POST", "/api/v1/projects", {
    client_id: kiwi.id,
    name: "Trail maps app",
    is_billable: true,
    bill_by: "project",
    hourly_rate: 16000,
    task_ids: [design.id, dev.id],
    membership_ids: [account, ivo.id],
  });
  await call(page, "POST", "/api/v1/time_entries", { membership_id: ivo.id, project_id: trailMaps.id, task_id: design.id, spent_date: day(0), duration_seconds: 4 * 3600, notes: "Map legend and icons" });
  await call(page, "POST", "/api/v1/time_entries", { project_id: trailMaps.id, task_id: dev.id, spent_date: day(0), duration_seconds: 2.5 * 3600, notes: "Offline tiles spike", billable: false });

  const travel = await call(page, "POST", "/api/v1/expense_categories", { name: "Travel" });
  await call(page, "POST", "/api/v1/expense_categories", { name: "Mileage", unit_name: "km", unit_price: 30 });
  // Dated today: the Expenses page opens on the current month.
  await call(page, "POST", "/api/v1/expenses", { project_id: relaunch.id, category_id: travel.id, spent_date: today, amount: 8450, notes: "Train to Hamburg", billable: true });

  const heading = (name: string | RegExp) => () => expect(page.getByRole("heading", { name }).first()).toBeVisible();

  await visit(page, `/day/${day(0)}`, "m1-time-day", () => expect(page.getByText("Homepage wireframes")).toBeVisible());
  // A person is one step away from every page: Help opens over the page, with the address to write to.
  await page.getByRole("button", { name: /^Help/ }).click();
  const help = page.getByRole("dialog", { name: "Help" });
  await expect(help.getByRole("link", { name: "hello@honestrobin.com" })).toHaveAttribute("href", "mailto:hello@honestrobin.com");
  await expectNoRawKeys(page, "help");
  await snap(page, "help");
  // From there, the owner is one more step from taking all the data with them.
  await help.getByRole("link", { name: "Export all data" }).click();
  await expect(page.getByRole("heading", { name: "Export all data" })).toBeVisible();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await visit(page, `/week/${day(0)}`, "m1-time-week", () => expect(page.getByText("Website relaunch").first()).toBeVisible());
  await visit(page, "/projects", "m1-projects", heading("Projects"));
  await visit(page, "/projects/new", "m1-project-new", heading("New project"));

  await visit(page, `/projects/${relaunch.id}`, "m1-project-overview", heading("Website relaunch"));
  for (const tab of ["Tasks", "Team", "Settings"]) {
    await page.getByRole("tab", { name: tab }).click();
    await expect(page.getByRole("tabpanel", { name: tab })).toBeVisible();
    await expectNoRawKeys(page, `project tab ${tab}`);
    await snap(page, `m1-project-${tab.toLowerCase()}`);
  }

  await visit(page, "/clients", "m1-clients", heading("Clients"));
  await page.getByRole("button", { name: "New client" }).first().click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await expectNoRawKeys(page, "new client dialog");
  await snap(page, "m1-client-dialog");
  await page.keyboard.press("Escape");

  await visit(page, "/tasks", "m1-tasks", heading("Tasks"));
  await page.getByRole("button", { name: "New task" }).first().click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await expectNoRawKeys(page, "new task dialog");
  await snap(page, "m1-task-dialog");
  await page.keyboard.press("Escape");

  await visit(page, "/team", "m1-team", heading("Team"));
  await visit(page, `/team/${ivo.id}`, "m1-person", () => expect(page.getByText("Ivo Designer").first()).toBeVisible());
  await visit(page, "/expenses", "m1-expenses", () => expect(page.getByText("Train to Hamburg")).toBeVisible());
  await visit(page, "/approvals", "m1-approvals", heading("Approvals"));
  await visit(page, "/settings/expense-categories", "m1-expense-categories", () => expect(page.getByRole("cell", { name: "Mileage", exact: true })).toBeVisible());
  await visit(page, "/settings/account", "m1-account-settings", heading("Account settings"));
  // The rounding direction only applies once rounding is on, and it is saved with the rest.
  await expect(page.getByLabel("Round", { exact: true })).toHaveCount(0);
  await page.getByLabel("Round time to").click();
  await page.getByRole("option", { name: "15 minutes" }).click();
  await page.getByLabel("Round", { exact: true }).click();
  await page.getByRole("option", { name: "To the nearest" }).click();
  await page.getByRole("button", { name: "Save changes" }).click();
  // Exact: the toast also announces itself to screen readers as "Notification Changes saved".
  await expect(page.getByText("Changes saved", { exact: true })).toBeVisible();
  expect((await call(page, "GET", "/api/v1/account")).time_rounding_mode).toBe("nearest");
  await snap(page, "m1-account-settings-rounding");
  // Moving out: one step starts a full export, built in the background, and shows where to go
  // next and what's still connected. It changes nothing in the account.
  await page.getByRole("button", { name: "Move out" }).click();
  await expect(page).toHaveURL(/\/settings\/move-out$/);
  await expect(page.getByRole("heading", { name: "Moving out changes nothing" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Where you can go next" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Still connected" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Download" }).first()).toBeVisible({ timeout: 30_000 });
  await expectNoRawKeys(page, "move out");
  await snap(page, "m6-move-out");
  const exportDownload = page.waitForEvent("download");
  await page.getByRole("button", { name: "Download" }).first().click();
  expect((await exportDownload).suggestedFilename()).toMatch(/^honest-robin-tour-co-\d{4}-\d{2}-\d{2}\.zip$/);
  expect((await call(page, "GET", "/api/v1/account")).status).toBe("active");
  await visit(page, "/settings/account", "m6-account-data", heading("Account settings"));
  await expect(page.getByRole("button", { name: "Download" }).first()).toBeVisible();
  await page.locator("#delete").scrollIntoViewIfNeeded();
  await page.getByRole("button", { name: "Delete account…" }).click();
  await expect(page.getByRole("button", { name: "Delete in 14 days" })).toBeDisabled();
  await snap(page, "m6-account-delete-dialog");
  await page.keyboard.press("Escape");
  if (config.edition === "cloud") await visit(page, "/settings/billing", "m6-billing", heading("Billing"));
  await visit(page, "/settings/profile", "m1-profile", heading("Your profile"));
  await visit(page, "/developers", "m6-developers", () => expect(page.getByText("/api/v1/time_entries").first()).toBeVisible());
  await visit(page, "/settings/audit", "m1-audit-log", heading("Audit log"));
  await visit(page, "/settings/import", "m2-import-start", heading("Import from Harvest"));

  // Reports, before invoicing takes the time off the uninvoiced report.
  const week = `unit=week&from=${day(0)}&to=${day(6)}`;
  await visit(page, `/reports?${week}`, "m6-report-time", () => expect(page.getByRole("button", { name: "Website relaunch" })).toBeVisible());
  await page.getByRole("button", { name: "Client", exact: true }).click();
  await expect(page.getByRole("button", { name: "Kiwi Outdoor Ltd" })).toBeVisible();
  await snap(page, "m6-report-time-by-client");
  // Drilling into a client lists its projects.
  await page.getByRole("button", { name: "Northwind Studio" }).click();
  await expect(page.getByText("Client:")).toBeVisible();
  await expect(page.getByRole("button", { name: "Trail maps app" })).toHaveCount(0);
  await visit(page, `/reports?tab=detailed&${week}`, "m6-report-detailed", () => expect(page.getByText("Map legend and icons")).toBeVisible());
  await page.getByRole("checkbox", { name: /Trail maps app/ }).first().check();
  await expect(page.getByText("1 entry selected")).toBeVisible();
  await snap(page, "m6-report-detailed-selected");
  await page.getByRole("button", { name: "Make non-billable" }).click();
  await expect(page.getByText("1 entry changed.", { exact: true })).toBeVisible();
  await visit(page, `/reports?tab=uninvoiced&${week}`, "m6-report-uninvoiced", () => expect(page.getByRole("link", { name: "Create invoice" }).first()).toBeVisible());
  await visit(page, "/reports?tab=budget", "m6-report-budget", () => expect(page.getByRole("link", { name: "Website relaunch" })).toBeVisible());
  await visit(page, "/reports?tab=expenses&unit=all", "m6-report-expenses", () => expect(page.getByText("Travel")).toBeVisible());
  const download = page.waitForEvent("download");
  await page.getByRole("button", { name: "Export" }).click();
  await page.getByRole("menuitem", { name: "Excel (.xlsx)" }).click();
  expect((await download).suggestedFilename()).toBe("expenses-by-category.xlsx");

  // M3: an invoice from the tracked time, sent, and opened from its public link.
  const invoice = await call(page, "POST", "/api/v1/invoices", { client_id: client.id, from_time: { grouping: "task", include_expenses: true } });
  await visit(page, "/invoices/new", "m3-invoice-new", heading("New invoice"));
  await visit(page, `/invoices/${invoice.id}`, "m3-invoice-draft", heading("Draft invoice"));
  const sent = await call(page, "POST", `/api/v1/invoices/${invoice.id}/mark_sent`);
  await visit(page, "/invoices", "m3-invoices", () => expect(page.getByText(sent.number).first()).toBeVisible());
  await visit(page, `/invoices/${invoice.id}`, "m3-invoice-sent", heading(`Invoice ${sent.number}`));
  await visit(page, "/settings/invoices", "m3-invoice-settings", heading("Invoice settings"));
  await visit(page, "/settings/payments", "m3-payments", heading("Online payments"));
  await visit(page, "/settings/accounting", "m5-accounting", heading("Accounting"));
  await visit(page, new URL(sent.public_url).pathname, "m3-invoice-public", () => expect(page.getByText("Download PDF")).toBeVisible());
});
