// SPDX-License-Identifier: AGPL-3.0-only
import { expect, test } from "@playwright/test";
import { atHome, freshWorkspace } from "./helpers";

/**
 * Time as most people get it: an instance with the default switches, where only the core shows.
 * The main run switches every feature on, so this spec skips there; CI runs it again after
 * restarting the app with the defaults.
 */
test("with the default switches, the menu is the core and a person is one step away", async ({ page, request }) => {
  const config = await (await request.get("/api/v1/auth/config")).json();
  test.skip((config.features ?? []).length > 0, "runs against an instance with the default switches");

  await freshWorkspace(page, request, "Core & Co");
  // The work pages are in the bar across the top; the settings pages behind Settings.
  const menu = page.getByRole("navigation", { name: "Main navigation" });
  for (const name of ["Track", "Reports", "Projects", "Clients"]) {
    await expect(menu.getByRole("link", { name: new RegExp(`^${name}`) }), name).toBeVisible();
  }
  await page.getByRole("button", { name: /^Settings/ }).click();
  const settings = page.getByRole("menu");
  for (const name of ["Account", "Profile"]) {
    await expect(settings.getByRole("menuitem", { name: new RegExp(`^${name}`) }), name).toBeVisible();
  }
  for (const name of ["Invoices", "Expenses", "Approvals", "Tasks", "Team", "Import from Harvest", "Invoice settings", "Online payments", "Accounting", "Expense categories"]) {
    await expect(menu.getByRole("link", { name: new RegExp(`^${name}`) }), name).toHaveCount(0);
    await expect(settings.getByRole("menuitem", { name: new RegExp(`^${name}`) }), name).toHaveCount(0);
  }
  await page.keyboard.press("Escape");

  // On a phone too, Help is in the bar.
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole("button", { name: /^Help/ }).click();
  await expect(page.getByRole("dialog", { name: "Help" }).getByRole("link", { name: "hello@honestrobin.com" })).toBeVisible();
  await page.keyboard.press("Escape");
  await page.setViewportSize({ width: 1280, height: 720 });

  // A switched-off page goes to Time.
  await page.goto("/invoices");
  await expect(page).toHaveURL(atHome);

  // A person is one step away, and the owner one more step from taking everything with them.
  await page.getByRole("button", { name: /^Help/ }).click();
  const help = page.getByRole("dialog", { name: "Help" });
  await expect(help.getByRole("link", { name: "hello@honestrobin.com" })).toHaveAttribute("href", "mailto:hello@honestrobin.com");
  await help.getByRole("link", { name: "Export all data" }).click();
  await expect(page.getByRole("heading", { name: "Move out" })).toBeVisible();
});
