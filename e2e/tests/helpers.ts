// SPDX-License-Identifier: AGPL-3.0-only
import { expect, type APIRequestContext, type Page } from "@playwright/test";

export const PASSWORD = "correct horse battery";

/** Time is the home page: signed in, people land on "/". */
export const atHome = (url: URL) => url.pathname === "/";

export function uniqueEmail(prefix = "user") {
  return `${prefix}-${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}@example.test`;
}

/** Saves a full-page screenshot to e2e/screenshots when SCREENSHOTS=1 (for design review). */
export async function snap(page: Page, name: string) {
  if (process.env.SCREENSHOTS) await page.screenshot({ path: `screenshots/${name}.png`, fullPage: true });
}

/** Calls the API from inside the page, so the session cookie, CSRF token and account are the app's own. */
export async function call<T = any>(page: Page, method: string, path: string, body?: unknown): Promise<T> {
  const res = await page.evaluate(
    async ({ method, path, body }) => {
      const xsrf = document.cookie.split("; ").find((c) => c.startsWith("XSRF-TOKEN="))?.slice("XSRF-TOKEN=".length);
      const headers: Record<string, string> = { "Content-Type": "application/json" };
      const account = localStorage.getItem("honestrobin.account");
      if (account) headers["HonestRobin-Account-Id"] = account;
      if (xsrf && method !== "GET") headers["X-XSRF-TOKEN"] = decodeURIComponent(xsrf);
      const r = await fetch(path, { method, headers, credentials: "include", body: body === undefined ? undefined : JSON.stringify(body) });
      const text = await r.text();
      return { ok: r.ok, status: r.status, text };
    },
    { method, path, body },
  );
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status} ${res.text}`);
  return res.text ? JSON.parse(res.text) : (undefined as T);
}

/**
 * Signs the page in to a workspace of its own: a new sign-up where sign-up is open; otherwise the
 * instance admin from 00-first-user opens a second workspace.
 */
export async function freshWorkspace(page: Page, request: APIRequestContext, name: string) {
  const config = await (await request.get("/api/v1/auth/config")).json();
  if (config.needs_setup || config.signup_allowed) {
    await page.goto("/signup");
    if (config.needs_setup) await page.getByLabel("Setup code").fill(process.env.HONESTROBIN_SETUP_CODE ?? "");
    await page.getByLabel("Your name").fill("Marta Owner");
    await page.getByLabel("Email").fill(uniqueEmail("tour"));
    await page.getByLabel("Password").fill(PASSWORD);
    await page.getByLabel("Workspace name").fill(name);
    await page.getByRole("button", { name: "Create workspace" }).click();
    await expect(page).toHaveURL(atHome);
  } else {
    await page.goto("/login");
    await page.getByLabel("Email").fill("owner@example.test");
    await page.getByLabel("Password").fill(PASSWORD);
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
    await expect(page).toHaveURL(atHome);
    const created = await call(page, "POST", "/api/v1/accounts", { name, default_currency: "EUR" });
    await page.evaluate((id) => localStorage.setItem("honestrobin.account", id), created.id);
    await page.reload();
    await expect(page.getByRole("button", { name: new RegExp(name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")) })).toBeVisible();
  }
}
