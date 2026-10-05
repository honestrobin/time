// SPDX-License-Identifier: AGPL-3.0-only
// "You can always leave" in the Robin's Code: a Move out button on every plan and in every state
// of an account, which shows what's still connected and changes nothing.
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, createRootRoute, createRouter, RouterProvider } from "@tanstack/react-router";
import { cleanup, render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, beforeAll, describe, expect, it } from "vitest";
import i18n from "../../i18n";
import type { Schemas } from "../../lib/api";
import { authConfigQuery, meQuery, type AuthConfig, type Me } from "../../lib/session";
import { AccountDataSection } from "./AccountData";
import { accountQuery } from "./AccountSettingsPage";
import { connectionsQuery, exportsQuery, MoveOutPage } from "./MoveOut";

/** [page], signed in as an admin of an account in [status], without a server. */
function renderPage(page: () => ReactNode, status: string, connections: Schemas["ConnectionView"][] = []) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  queryClient.setQueryData(meQuery.queryKey, {
    name: "Marta Owner",
    email: "marta@example.test",
    role: "admin",
    accounts: [{ id: "account-1", name: "Tour & Co" }],
    current_account_id: "account-1",
    current_membership_id: "membership-1",
    two_factor_setup_required: false,
  } as unknown as Me);
  queryClient.setQueryData(authConfigQuery.queryKey, { edition: "selfhost" } as unknown as AuthConfig);
  queryClient.setQueryData(accountQuery.queryKey, {
    id: "account-1",
    name: "Tour & Co",
    status,
    deletes_at: status === "pending_deletion" ? "2026-10-19T09:00:00Z" : undefined,
  });
  queryClient.setQueryData(exportsQuery.queryKey, []);
  queryClient.setQueryData(connectionsQuery.queryKey, connections);
  const rootRoute = createRootRoute({ component: page });
  const router = createRouter({ routeTree: rootRoute, history: createMemoryHistory({ initialEntries: ["/"] }) });
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

describe("Move out", () => {
  it("the Move out button is in Settings → Account, in every state of an account", async () => {
    for (const status of ["active", "lapsed", "pending_deletion"]) {
      renderPage(AccountDataSection, status);
      expect(await screen.findByRole("button", { name: "Move out" }), status).toBeTruthy();
      expect(screen.getByRole("heading", { name: "Move out" }), status).toBeTruthy();
      cleanup();
    }
  });

  it("the Move out page lists what's still connected, how to end each, and that moving out changes nothing", async () => {
    renderPage(MoveOutPage, "lapsed", [
      { kind: "api_token", name: "Scripts", membership_id: "membership-1", person: "Marta Owner", last_used_at: "2026-10-01T09:00:00Z" },
      { kind: "device", name: "Browser extension (Firefox)", membership_id: "membership-2", person: "Ivo Designer" },
      { kind: "invitation", name: "Ana Manager", membership_id: "membership-3", person: "Ana Manager", ends_at: "2026-10-19T09:00:00Z", end_in: "/team" },
      { kind: "stripe", name: "Tour & Co Ltd", mode: "connect", end_in: "/settings/payments" },
      { kind: "invoice_links", count: 3 },
    ]);
    expect(await screen.findByRole("heading", { name: "Moving out changes nothing" })).toBeTruthy();
    expect(screen.getByRole("heading", { name: "Where you can go next" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Move out" })).toBeTruthy();
    expect(screen.getByText("API token “Scripts”")).toBeTruthy();
    expect(screen.getByText("Revoke it in your profile, under Personal access tokens.")).toBeTruthy();
    expect(screen.getByText("Signed-in device “Browser extension (Firefox)”")).toBeTruthy();
    expect(screen.getByText("Ivo Designer revokes it in their profile, or you deactivate Ivo Designer in Team, which stops all their tokens.")).toBeTruthy();
    expect(screen.getByText("Disconnect Stripe under Online payments. That ends our access at Stripe.")).toBeTruthy();
    expect(screen.getByText("Invitation for Ana Manager")).toBeTruthy();
    expect(screen.getByText("3 issued invoices each have a link that opens it for anyone who has it, such as your client.")).toBeTruthy();
    expect(screen.getByText(/^This account is read-only/)).toBeTruthy();
    expect(screen.getByRole("link", { name: "Online payments" }).getAttribute("href")).toBe("/settings/payments");
    expect(screen.getByRole("link", { name: "Delete the account, under Settings → Account" }).getAttribute("href")).toBe("/settings/account#delete");
  });
});
