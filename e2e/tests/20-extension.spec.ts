// SPDX-License-Identifier: AGPL-3.0-only
// M4, the browser extension, loaded into Chromium: signing in through the device flow, the timer
// in both directions (AT-4.1), and the Track time button on fixture pages of all five sites (AT-4.2).
// Needs the e2e build: pnpm --filter @honestrobin/extension build:e2e
import { chromium, expect, test, type BrowserContext, type Page } from "@playwright/test";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { call, freshWorkspace, snap } from "./helpers";

const extensionDir = path.resolve(import.meta.dirname, "../../extension/dist/chrome");
const fixtures = path.resolve(import.meta.dirname, "../../extension/test/fixtures");
const base = process.env.HONESTROBIN_E2E_BASE_URL ?? "http://localhost:8080";

test.describe.configure({ mode: "serial" });
test.skip(!existsSync(path.join(extensionDir, "manifest.json")), "Build the extension first: pnpm --filter @honestrobin/extension build:e2e");

let context: BrowserContext;
let app: Page;
let popup: Page;
let extensionId: string;

test.beforeAll(async ({ playwright }) => {
  test.setTimeout(120_000);
  context = await chromium.launchPersistentContext("", {
    channel: "chromium",
    baseURL: base,
    args: [`--disable-extensions-except=${extensionDir}`, `--load-extension=${extensionDir}`],
  });
  const worker = context.serviceWorkers()[0] ?? (await context.waitForEvent("serviceworker"));
  extensionId = new URL(worker.url()).host;

  app = await context.newPage();
  const request = await playwright.request.newContext({ baseURL: base });
  await freshWorkspace(app, request, "Extension & Co");
  const me = await call(app, "GET", "/api/v1/me");
  const task = await call(app, "POST", "/api/v1/tasks", { name: "Development", default_billable: true, is_default: true });
  const client = await call(app, "POST", "/api/v1/clients", { name: "Fjord Studio", currency: "EUR" });
  await call(app, "POST", "/api/v1/projects", { client_id: client.id, name: "Mobile app", task_ids: [task.id], membership_ids: [me.current_membership_id] });
});

test.afterAll(async () => {
  await context?.close();
});

test("the extension signs in with a code approved in the web app", async () => {
  popup = await context.newPage();
  await popup.goto(`chrome-extension://${extensionId}/popup.html`);
  await popup.setViewportSize({ width: 372, height: 560 });
  await popup.getByLabel("Your Honest Robin address").fill(base);
  await snap(popup, "m4-popup-sign-in");
  const approvalTab = context.waitForEvent("page");
  await popup.getByRole("button", { name: "Sign in", exact: true }).click();
  const approval = await approvalTab;
  const code = (await popup.getByLabel("Your code").textContent())!.trim();
  await expect(approval.getByRole("heading", { name: "Connect a device" })).toBeVisible();
  await expect(approval.getByText(code)).toBeVisible();
  await expect(approval.getByText(/Browser extension \(Chrome\) wants to track time as you in Extension & Co/)).toBeVisible();
  await snap(approval, "m4-device-approval");
  await approval.getByRole("button", { name: "Connect" }).click();
  await expect(approval.getByText(/is connected/)).toBeVisible();
  await approval.close();
  // The background collects the token; the popup follows.
  await expect(popup.getByText("Extension & Co")).toBeVisible({ timeout: 10_000 });
  await expect(popup.getByRole("button", { name: "Start timer" })).toBeVisible();
});

test("a timer started in the extension shows in the web app within 2 s, and the other way round (AT-4.1)", async () => {
  await app.goto("/time");
  await expect(app.locator(".timer-strip")).toHaveCount(0);

  await popup.getByLabel("Notes").fill("From the toolbar");
  await popup.getByRole("button", { name: "Start timer" }).click();
  await expect(popup.locator(".running")).toContainText("Mobile app");
  await snap(popup, "m4-popup-running");
  await expect(app.locator(".timer-strip")).toContainText("From the toolbar", { timeout: 2_000 });

  // Stopped in the web app: the popup notices.
  await app.locator(".timer-strip").getByRole("button", { name: "Stop" }).click();
  await expect(popup.locator(".running")).toHaveCount(0, { timeout: 2_000 });

  // Started in the web app: the popup shows it.
  const me = await call(app, "GET", "/api/v1/me/assignments");
  await call(app, "POST", "/api/v1/time_entries", { project_id: me[0].project_id, task_id: me[0].tasks[0].task_id, spent_date: new Date().toISOString().slice(0, 10), notes: "From the web app" });
  await expect(popup.locator(".running")).toContainText("From the web app", { timeout: 2_000 });
  await popup.locator(".running").getByRole("button", { name: "Stop" }).click();
  await expect(popup.locator(".running")).toHaveCount(0);
});

const sites: Record<string, { host: string; source: string; id: string; title: string }> = {
  "github-issue": { host: "https://github.com", source: "github", id: "acme/rocket#42", title: "Login button misaligned on Safari" },
  "github-pr": { host: "https://github.com", source: "github", id: "acme/rocket#7", title: "Add dark mode to settings" },
  jira: { host: "https://acme.atlassian.net", source: "jira", id: "ROCK-128", title: "Payment webhook retries" },
  "jira-board": { host: "https://acme.atlassian.net", source: "jira", id: "ROCK-7", title: "Rate limit the export endpoint" },
  asana: { host: "https://app.asana.com", source: "asana", id: "1200000000000003", title: "Write onboarding copy" },
  linear: { host: "https://linear.app", source: "linear", id: "ENG-311", title: "Sync stalls on large boards" },
  trello: { host: "https://trello.com", source: "trello", id: "AbC123xy", title: "Design review" },
};

for (const [fixture, expected] of Object.entries(sites)) {
  test(`the Track time button works on ${fixture} and stores the external reference (AT-4.2)`, async () => {
    const html = readFileSync(path.join(fixtures, `${fixture}.html`), "utf8");
    const url = /name="fixture-url" content="([^"]+)"/.exec(html)![1];
    const page = await context.newPage();
    await page.route(`${expected.host}/**`, (route) => route.fulfill({ status: 200, contentType: "text/html", body: html }));
    await page.goto(url);

    const button = page.locator("honestrobin-track").getByRole("button", { name: "Track time" });
    await expect(button).toBeVisible();
    await button.click();
    const panel = page.locator("honestrobin-track").getByRole("dialog");
    await expect(panel.getByLabel("Notes")).toHaveValue(expected.title);
    await snap(page, `m4-${fixture}-panel`);
    await panel.getByRole("button", { name: "Start timer" }).click();
    await expect(page.locator("honestrobin-track").getByRole("button", { name: "Stop timer" })).toBeVisible();

    const running = await call(app, "GET", "/api/v1/me/timer");
    expect(running.notes).toBe(expected.title);
    expect(running.external_reference).toMatchObject({ source: expected.source, id: expected.id, title: expected.title });
    expect(running.external_reference.url).toMatch(/^https:\/\//);

    await page.locator("honestrobin-track").getByRole("button", { name: "Stop timer" }).click();
    await expect(page.locator("honestrobin-track").getByRole("button", { name: "Track time" })).toBeVisible();
    await page.close();
  });
}
