// SPDX-License-Identifier: AGPL-3.0-only
import { expect, test } from "@playwright/test";
import { call, freshWorkspace } from "./helpers";

/**
 * "Enter as you go", the way a person uses it: a first entry with a new project, client and task,
 * one mistake, then the fix. A mistake costs nothing: what was typed stays, and nothing is
 * created until everything is valid.
 */
test("a mistake keeps what was typed and creates nothing; the fix saves it all at once", async ({ page, request }) => {
  await freshWorkspace(page, request, "Quick & Co");
  await page.goto("/");
  await page.getByRole("button", { name: /Add time by hand/ }).click();

  const dialog = page.getByRole("dialog", { name: "New entry" });
  await dialog.getByLabel("Project name").fill("Harbour website");
  await dialog.getByLabel("Client name", { exact: true }).fill("Harbour Ltd");
  await dialog.getByLabel("Task name").fill("Design");
  await dialog.getByLabel("Notes").fill("Homepage layout");
  // The mistake: 25 hours instead of 2.5.
  const time = dialog.getByLabel("Time", { exact: true });
  await time.fill("25:00");
  await time.press("Tab");
  await dialog.getByRole("button", { name: "Save entry" }).click();

  await expect(dialog.getByText("An entry can't be longer than 24 hours").first()).toBeVisible();
  await expect(dialog.getByLabel("Project name")).toHaveValue("Harbour website");
  await expect(dialog.getByLabel("Client name", { exact: true })).toHaveValue("Harbour Ltd");
  await expect(dialog.getByLabel("Task name")).toHaveValue("Design");
  await expect(dialog.getByLabel("Notes")).toHaveValue("Homepage layout");
  expect((await call(page, "GET", "/api/v1/clients")).data, "no client yet").toHaveLength(0);
  expect((await call(page, "GET", "/api/v1/projects")).data, "no project yet").toHaveLength(0);
  expect((await call(page, "GET", "/api/v1/tasks")).data, "no task yet").toHaveLength(0);

  await time.fill("2:30");
  await time.press("Tab");
  await dialog.getByRole("button", { name: "Save entry" }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByText("Harbour website").first()).toBeVisible();

  const entries = (await call(page, "GET", "/api/v1/time_entries")).data;
  expect(entries).toHaveLength(1);
  expect(entries[0].duration_seconds).toBe(9000);
  expect(entries[0].notes).toBe("Homepage layout");
  expect((await call(page, "GET", "/api/v1/clients")).data.map((c: { name: string }) => c.name)).toEqual(["Harbour Ltd"]);
  expect((await call(page, "GET", "/api/v1/tasks")).data.map((t: { name: string }) => t.name)).toEqual(["Design"]);
});

test("an entry saved with Billable unticked isn't billable", async ({ page, request }) => {
  await freshWorkspace(page, request, "Unticked & Co");
  // A first entry, so the dialog starts on its billable project and task.
  await call(page, "POST", "/api/v1/time_entries/quick", {
    project_name: "Harbour website",
    client_name: "Harbour Ltd",
    task_name: "Design",
    duration_seconds: 3600,
  });
  await page.goto("/");
  await page.getByRole("button", { name: /Add time by hand/ }).click();

  const dialog = page.getByRole("dialog", { name: "New entry" });
  const billable = dialog.getByRole("checkbox", { name: "Billable" });
  await expect(billable).toBeChecked();
  await billable.click();
  await expect(billable).not.toBeChecked();
  await dialog.getByLabel("Notes").fill("Pro bono fix");
  const time = dialog.getByLabel("Time", { exact: true });
  await time.fill("0:30");
  await time.press("Tab");
  await dialog.getByRole("button", { name: "Save entry" }).click();
  await expect(dialog).toBeHidden();

  const entries: { notes: string; billable: boolean }[] = (await call(page, "GET", "/api/v1/time_entries")).data;
  expect(entries.find((e) => e.notes === "Pro bono fix")?.billable, "the unticked entry").toBe(false);
  expect(entries.find((e) => e.notes !== "Pro bono fix")?.billable, "the first entry").toBe(true);
});
