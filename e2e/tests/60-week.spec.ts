// SPDX-License-Identifier: AGPL-3.0-only
import { expect, test } from "@playwright/test";
import { call, freshWorkspace } from "./helpers";

interface Entry {
  spent_date: string;
  duration_seconds: number;
}

/** The days of the account's week that holds [today], as the app counts them. */
function weekOf(today: string, weekStart: number): string[] {
  const at = (iso: string, days: number) => {
    const d = new Date(`${iso}T00:00:00Z`);
    d.setUTCDate(d.getUTCDate() + days);
    return d.toISOString().slice(0, 10);
  };
  const weekday = new Date(`${today}T00:00:00Z`).getUTCDay() || 7;
  const start = at(today, -((weekday - weekStart + 7) % 7));
  return Array.from({ length: 7 }, (_, i) => at(start, i));
}

/**
 * The week, the way a person uses it: type hours into a day, get one wrong and fix it, pick a day
 * and open it, then look at the same week as strips of paper. A typo saves nothing and stays in its
 * cell until it's fixed.
 */
test("The week: type hours into a day, fix a typo, pick a day, see it as strips", async ({ page, request }) => {
  await freshWorkspace(page, request, "Week & Co");
  await call(page, "POST", "/api/v1/time_entries/quick", { client_name: "Harbour Ltd", project_name: "Harbour website", task_name: "Design", duration_seconds: 1800 });
  const account = await call(page, "GET", "/api/v1/account");
  const today: string = new Intl.DateTimeFormat("en-CA", { timeZone: account.timezone }).format(new Date());
  const days = weekOf(today, account.week_start);
  // Another day of this week: yesterday, unless the week starts today.
  const other = days.filter((d) => d < today).pop() ?? days[1];
  const column = days.indexOf(other);

  await page.goto(`/week/${today}`);
  const table = page.getByRole("table");
  const row = table.getByRole("row").filter({ hasText: "Harbour website" });
  const total = table.locator("tfoot .week-total");
  await expect(total).toHaveText("0:30");
  // Today is the chosen day when the week opens.
  await expect(table.locator("thead .week-pick").nth(days.indexOf(today))).toHaveAttribute("aria-pressed", "true");

  // The typo: it stays in the cell, says what's wrong, and saves nothing.
  const cell = row.getByRole("textbox").nth(column);
  await cell.fill("half an hour");
  await cell.press("Tab");
  await expect(cell).toHaveValue("half an hour");
  await expect(cell).toHaveAttribute("aria-invalid", "true");
  await expect(page.getByText("“half an hour” isn't a time. Type 1:30 or 1.5, and the totals catch up.")).toBeVisible();
  await expect(total).toHaveText("0:30");
  const saved = async () => ((await call(page, "GET", `/api/v1/time_entries?from=${other}&to=${other}`)).data as Entry[]).map((e) => e.duration_seconds);
  expect(await saved()).toEqual([]);

  // The fix: the totals catch up, and the day is now the week's longest, so its bar is full.
  await cell.fill("1.5");
  await cell.press("Tab");
  await expect(total).toHaveText("2:00");
  await expect(cell).toHaveValue("1:30");
  await expect(page.getByText(/isn't a time/)).toHaveCount(0);
  await expect(table.locator("tfoot .d-fill > span").nth(column)).toHaveAttribute("style", /width: 100%/);
  expect(await saved()).toEqual([5400]);

  // Pick that day: it's shaded, and Day opens it. Week comes back to it.
  await table.locator("thead .week-pick").nth(column).click();
  await expect(page).toHaveURL(new RegExp(`/week/${other}$`));
  await expect(table.locator("thead .week-pick").nth(column)).toHaveAttribute("aria-pressed", "true");
  await page.getByRole("group", { name: "Show", exact: true }).getByRole("link", { name: "Day" }).click();
  await expect(page).toHaveURL(new RegExp(`/day/${other}$`));
  await page.getByRole("group", { name: "Show", exact: true }).getByRole("link", { name: "Week" }).click();
  await expect(page).toHaveURL(new RegExp(`/week/${other}$`));

  // The same week as strips: the chosen day is the tall one, with its project written in.
  await page.getByRole("group", { name: "Show as" }).getByRole("button", { name: "Strip" }).click();
  const strips = page.getByRole("region", { name: /^The week as strips of paper/ });
  await expect(strips).toBeVisible();
  const chosen = strips.getByRole("button", { pressed: true });
  await expect(chosen).toHaveCount(1);
  await expect(chosen).toHaveAccessibleName(/1:30 tracked$/);
  await expect(chosen).toContainText("Harbour website");
  await expect(page.getByText("Time typed in without a start time sits at the end of its day.")).toBeVisible();

  // The choice is remembered in this browser, and Table is one click back.
  await page.reload();
  await expect(strips).toBeVisible();
  await page.getByRole("group", { name: "Show as" }).getByRole("button", { name: "Table" }).click();
  await expect(table).toBeVisible();
});
