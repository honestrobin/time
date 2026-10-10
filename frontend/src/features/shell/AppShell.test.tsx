// SPDX-License-Identifier: AGPL-3.0-only
// "A person answers" in the Robin's Code: a person is one step away from every page.
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, createRootRoute, createRoute, createRouter, RouterProvider } from "@tanstack/react-router";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import i18n from "../../i18n";
import { FEATURES } from "../../lib/features";
import { authConfigQuery, meQuery, type AuthConfig, type Me } from "../../lib/session";
import { peopleQuery } from "../team/shared";
import { AppShell, initials } from "./AppShell";
import { HELP_EMAIL } from "./Help";
import { navSections, OUT_OF_MENU } from "./nav";

// The parts of the shell that poll or stream from the server. Help doesn't depend on them.
vi.mock("../time/LiveUpdates", () => ({ LiveUpdates: () => null }));
vi.mock("../time/RunningTimer", () => ({ RunningTimerTab: () => null, TimerPill: () => null }));
vi.mock("../settings/AccountData", () => ({ AccountStatusBanner: () => null, EmailVerificationBanner: () => null }));
vi.mock("../settings/TwoFactorGate", () => ({ TwoFactorGate: () => null }));
vi.mock("../auth/ReauthDialog", () => ({ ReauthDialog: () => null }));

const ADMIN: Parameters<typeof navSections>[0] = {
  role: "admin",
  isAdmin: true,
  isManager: false,
  isManagerOrAdmin: true,
  canSeeRates: true,
  canManageProjects: true,
  canManageInvoices: true,
};
/**
 * Every page in the navigation, from the fullest menu there is: an admin's, in the cloud edition,
 * with every feature switched on, and the pages reached from Settings and Profile.
 */
const PAGES = [...navSections(ADMIN, "cloud", FEATURES).flatMap((section) => section.items.map((item) => item.to)), ...OUT_OF_MENU];

/** The real shell around a stand-in page, signed in, without a server. Every feature is on unless [features] says otherwise. */
function renderShellAt(
  path: string,
  { role = "member", edition = "selfhost", features = [...FEATURES] as string[], people = [{ id: "membership-1", name: "Marta Owner" }] } = {},
) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  queryClient.setQueryData(meQuery.queryKey, {
    name: "Marta Owner",
    email: "marta@example.test",
    role,
    accounts: [{ id: "account-1", name: "Tour & Co" }],
    current_account_id: "account-1",
    two_factor_setup_required: false,
  } as unknown as Me);
  queryClient.setQueryData(authConfigQuery.queryKey, { edition, features } as unknown as AuthConfig);
  queryClient.setQueryData(peopleQuery.queryKey, people as never);
  const rootRoute = createRootRoute({ component: AppShell });
  const pages = PAGES.map((p) => createRoute({ getParentRoute: () => rootRoute, path: p, component: () => <h1>{p}</h1> }));
  const router = createRouter({ routeTree: rootRoute.addChildren(pages), history: createMemoryHistory({ initialEntries: [path] }) });
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

beforeAll(async () => {
  if (!i18n.isInitialized) await new Promise((done) => i18n.on("initialized", done));
});

afterEach(cleanup);

describe("The menu", () => {
  it("with the default switches, shows the team only while the account has other people", async () => {
    renderShellAt("/", { role: "admin", features: [] });
    await screen.findByRole("heading", { name: "/" });
    expect(screen.queryByRole("link", { name: /^Team/ })).toBeNull();
    cleanup();
    renderShellAt("/", { role: "admin", features: [], people: [{ id: "membership-1", name: "Marta Owner" }, { id: "membership-2", name: "Ivo Designer" }] });
    await screen.findByRole("heading", { name: "/" });
    expect(await screen.findByRole("link", { name: /^Team/ })).toBeTruthy();
  });
});

describe("Help", () => {
  it("a person is one step away from every page, in both editions", { timeout: 30_000 }, async () => {
    for (const edition of ["selfhost", "cloud"]) {
      for (const path of PAGES) {
        renderShellAt(path, { edition });
        await screen.findByRole("heading", { name: path });
        fireEvent.click(screen.getByRole("button", { name: /^Help/ }));
        const email = await screen.findByRole("link", { name: HELP_EMAIL });
        expect(email.getAttribute("href"), `${edition}, ${path}`).toBe(`mailto:${HELP_EMAIL}`);
        cleanup();
      }
    }
  });

  it("with the default switches too, a person is one step away from every page of the core", async () => {
    const core = [...navSections(ADMIN, "selfhost", []).flatMap((section) => section.items.map((item) => item.to)), ...OUT_OF_MENU];
    for (const path of core) {
      renderShellAt(path, { role: "admin", features: [] });
      await screen.findByRole("heading", { name: path });
      fireEvent.click(screen.getByRole("button", { name: /^Help/ }));
      expect((await screen.findByRole("link", { name: HELP_EMAIL })).getAttribute("href"), path).toBe(`mailto:${HELP_EMAIL}`);
      cleanup();
    }
  });

  it("the ? key opens help too", async () => {
    renderShellAt("/reports");
    await screen.findByRole("heading", { name: "/reports" });
    fireEvent.keyDown(document.body, { key: "?" });
    expect((await screen.findByRole("link", { name: HELP_EMAIL })).getAttribute("href")).toBe(`mailto:${HELP_EMAIL}`);
  });

  it("help takes admins to exporting all data in one more step", async () => {
    renderShellAt("/", { role: "admin" });
    fireEvent.click(await screen.findByRole("button", { name: /^Help/ }));
    const exportLink = await screen.findByRole("link", { name: "Export all data" });
    expect(exportLink.getAttribute("href")).toBe("/settings/account#export");
  });
});

describe("Settings", () => {
  it("shows the person's initials", () => {
    expect(initials("Marta Owner")).toBe("MO");
    expect(initials("Ana")).toBe("A");
    expect(initials("  ana maría de la vega ")).toBe("AV");
    expect(initials("")).toBe("?");
  });
});
