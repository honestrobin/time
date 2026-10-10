// SPDX-License-Identifier: AGPL-3.0-only
import { expect, test } from "@playwright/test";
import { call, freshWorkspace } from "./helpers";

interface Entry {
  id: string;
  notes?: string;
  is_running: boolean;
  project: { name: string };
}

/**
 * Track, the way a person uses it: say what you're working on and press Enter, notice the wrong
 * project and fix it while the timer runs, stop with S, continue later. Mistakes included: a
 * refused start keeps what was typed and starts nothing.
 */
test("Track: start with Enter, fix the project while it runs, stop with S, continue", async ({ page, request }) => {
  await freshWorkspace(page, request, "Track & Co");
  // Two projects with time today, so there's a wrong one to start on.
  await call(page, "POST", "/api/v1/time_entries/quick", { client_name: "Harbour Ltd", project_name: "Harbour website", task_name: "Design", duration_seconds: 1800 });
  await call(page, "POST", "/api/v1/time_entries/quick", { client_name: "Kiwi Outdoor", project_name: "Trail maps", task_name: "Design", duration_seconds: 900 });
  await page.goto("/");

  // One key: type what you're doing and press Enter. It runs on the project used last.
  const ask = page.getByRole("form", { name: "Timer" }).getByLabel("What are you working on?");
  await ask.fill("Homepage layout");
  await ask.press("Enter");
  const timer = page.getByRole("region", { name: "Timer" });
  await expect(timer).toContainText("Homepage layout");
  await expect(timer).toContainText("Kiwi Outdoor · Trail maps");
  await expect(page).toHaveTitle(/^\d+:\d\d · Trail maps · Time$/);

  // Every other page shows it too, one click from the timer.
  await page.getByRole("link", { name: /^Reports/ }).click();
  await page.getByRole("link", { name: /^A timer is running: \d+:\d\d, Trail maps/ }).click();
  await expect(timer).toBeVisible();

  // The mistake: the wrong project. Open the running entry and move it; the timer keeps going.
  await timer.getByRole("button", { name: /Homepage layout/ }).click();
  const dialog = page.getByRole("dialog", { name: "Edit entry" });
  await dialog.getByRole("combobox", { name: "Project" }).click();
  await page.getByRole("option", { name: "Harbour website" }).click();
  await dialog.getByRole("button", { name: "Save" }).click();
  await expect(dialog).toBeHidden();
  await expect(timer).toContainText("Harbour Ltd · Harbour website");
  await expect(page).toHaveTitle(/^\d+:\d\d · Harbour website · Time$/);

  // S stops it, quietly. The tab goes back, and the entry shows when it ran.
  await page.keyboard.press("s");
  await expect(timer).toHaveCount(0);
  await expect(page).toHaveTitle("Honest Robin: Time");
  const row = page.getByRole("listitem").filter({ hasText: "Homepage layout" });
  await expect(row).toContainText(/\d?\d:\d\d\D* – \d?\d:\d\d/);
  // Today is shaded in the week, whichever address shows it, and as the week's longest day its bar is full.
  const week = page.getByRole("navigation", { name: "This week" });
  await expect(week.locator(".is-selected")).toHaveCount(1);
  await expect(week.locator(".is-selected .d-fill > span")).toHaveAttribute("style", /width: 100%/);

  // Continue starts a new entry on the same project with the same note, so the first range stays true.
  await row.getByRole("button", { name: /Continue/ }).click();
  await expect(timer).toContainText("Homepage layout");
  const entries: Entry[] = (await call(page, "GET", "/api/v1/time_entries")).data;
  const layout = entries.filter((e) => e.notes === "Homepage layout");
  expect(layout).toHaveLength(2);
  expect(layout.filter((e) => e.is_running)).toHaveLength(1);
  expect(layout.every((e) => e.project.name === "Harbour website")).toBe(true);
  await timer.getByRole("button", { name: "Stop" }).click();
  await expect(timer).toHaveCount(0);
});

test("Track: a refused start keeps what was typed and starts nothing", async ({ page, request }) => {
  await freshWorkspace(page, request, "Refused & Co");
  const first = await call(page, "POST", "/api/v1/time_entries/quick", { client_name: "Harbour Ltd", project_name: "Harbour website", task_name: "Design", duration_seconds: 1800 });
  await call(page, "POST", "/api/v1/time_entries/quick", { client_name: "Kiwi Outdoor", project_name: "Trail maps", task_name: "Design", duration_seconds: 900 });
  await page.goto("/");
  const ask = page.getByRole("form", { name: "Timer" }).getByLabel("What are you working on?");
  await ask.fill("Offline tiles");
  await page.getByRole("combobox", { name: "Project" }).click();
  await page.getByRole("option", { name: "Harbour website" }).click();

  // Meanwhile, in another tab, the project is archived.
  await call(page, "PATCH", `/api/v1/projects/${first.project.id}`, { is_active: false });
  await page.getByRole("button", { name: "Start" }).click();
  await expect(page.getByRole("alert")).toContainText("The timer didn't start.");
  await expect(ask).toHaveValue("Offline tiles");
  expect(((await call(page, "GET", "/api/v1/time_entries")).data as Entry[]).some((e) => e.is_running), "nothing runs").toBe(false);

  // The fix: another project, and start again. The note was there all along.
  await page.getByRole("combobox", { name: "Project" }).click();
  await page.getByRole("option", { name: "Trail maps" }).click();
  await page.getByRole("button", { name: "Start" }).click();
  const timer = page.getByRole("region", { name: "Timer" });
  await expect(timer).toContainText("Offline tiles");
  await expect(timer).toContainText("Kiwi Outdoor · Trail maps");
  await page.keyboard.press("s");
  await expect(timer).toHaveCount(0);
});
