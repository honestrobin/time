// SPDX-License-Identifier: AGPL-3.0-only
import { expect, test } from "@playwright/test";
import { atHome, PASSWORD, snap } from "./helpers";

// A retry would find the instance already set up and skip, hiding the failure.
test.describe.configure({ retries: 0 });

/**
 * AT-0.1: on a fresh instance (`docker compose up`), the first visitor sets up the
 * instance and becomes admin. Must run first, against an empty database.
 */
test("first user sets up the instance and becomes admin", async ({ page, request }) => {
  const config = await (await request.get("/api/v1/auth/config")).json();
  test.skip(!config.needs_setup, "Instance already set up; AT-0.1 needs a fresh database");

  await page.goto("/");
  await expect(page).toHaveURL(/\/signup$/);
  await expect(page.getByRole("heading", { name: "Set up Honest Robin" })).toBeVisible();
  await snap(page, "setup");

  // Only someone who can read the server's log can set it up (CI sets the code).
  await page.getByLabel("Setup code").fill(process.env.HONESTROBIN_SETUP_CODE ?? "");
  await page.getByLabel("Your name").fill("Marta Owner");
  await page.getByLabel("Email").fill("owner@example.test");
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByLabel("Workspace name").fill("Owner & Co");
  await page.getByRole("button", { name: "Create workspace" }).click();

  await expect(page).toHaveURL(atHome);
  await expect(page.getByRole("button", { name: /Owner & Co/ })).toBeVisible();
  await snap(page, "time-empty");

  // Admin-only settings are available to the first user.
  await page.getByRole("button", { name: /Owner & Co/ }).click();
  await page.getByRole("menuitem", { name: "Account", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Account settings" })).toBeVisible();
  await snap(page, "account-settings");

  const after = await (await request.get("/api/v1/auth/config")).json();
  expect(after.needs_setup).toBe(false);
  expect(after.signup_allowed).toBe(false);
});

test("sign out and back in", async ({ page }) => {
  await page.goto("/login");
  await snap(page, "login");
  await page.getByLabel("Email").fill("owner@example.test");
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page).toHaveURL(atHome);

  await page.getByRole("button", { name: /Owner & Co/ }).click();
  await page.getByRole("menuitem", { name: "Sign out" }).click();
  await expect(page).toHaveURL(/\/login/);
});
