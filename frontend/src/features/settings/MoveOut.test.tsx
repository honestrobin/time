// SPDX-License-Identifier: AGPL-3.0-only
// "You can always leave" in the Robin's Code: a Move out button on every plan and in every state
// of an account, which shows what's still connected and changes nothing.
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, createRootRoute, createRouter, RouterProvider } from "@tanstack/react-router";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import i18n from "../../i18n";
import { api, type Schemas } from "../../lib/api";
import { formatDate } from "../../lib/format";
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

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const CONNECTIONS: Schemas["ConnectionView"][] = [
  { kind: "api_token", id: "token-1", name: "Scripts", membership_id: "membership-1", person: "Marta Owner", last_used_at: "2026-10-01T09:00:00Z", end_in: "/settings/move-out" },
  { kind: "device", id: "token-2", name: "Browser extension (Firefox)", membership_id: "membership-2", person: "Ivo Designer", end_in: "/settings/move-out" },
  { kind: "invitation", name: "Ana Manager", membership_id: "membership-3", person: "Ana Manager", ends_at: "2026-10-19T09:00:00Z", end_in: "/settings/move-out" },
  { kind: "stripe", name: "Tour & Co Ltd", mode: "connect", end_in: "/settings/payments" },
  { kind: "harvest_import", id: "import-7", name: "7654321", end_in: "/settings/import#import-import-7" },
  { kind: "invoice_links", count: 3 },
];

const READ_ONLY_NOTE =
  "This account is read-only. You can still end each of these, here or on the page it links to, except invoice links, which end only when the account is deleted. You can also deactivate people in Team; once few enough can sign in for the free plan, the account works again.";
// An account waiting to be deleted can't deactivate people until the deletion is cancelled.
const READ_ONLY_NOTE_DELETING =
  "This account is read-only. You can still end each of these, here or on the page it links to, except invoice links, which end only when the account is deleted. Deactivating people waits until the account is active again.";

/** Stands in for the server's answer to a DELETE, and records what was called. */
function spyOnDelete() {
  return vi.spyOn(api, "DELETE").mockResolvedValue({ data: undefined, error: undefined, response: new Response(null, { status: 204 }) } as never);
}

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
    renderPage(MoveOutPage, "lapsed", CONNECTIONS);
    expect(await screen.findByRole("heading", { name: "Moving out changes nothing" })).toBeTruthy();
    expect(screen.getByRole("heading", { name: "Where you can go next" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Move out" })).toBeTruthy();
    expect(screen.getByText("API token “Scripts”")).toBeTruthy();
    expect(screen.getByText("Revoke it here, or in your profile under Personal access tokens.")).toBeTruthy();
    expect(screen.getByText("Signed-in device “Browser extension (Firefox)”")).toBeTruthy();
    expect(screen.getByText("Revoke it here. Ivo Designer can also revoke it in their profile.")).toBeTruthy();
    // Read-only or not, each token, device and invitation can be ended right here.
    expect(screen.getAllByRole("button", { name: "Revoke" })).toHaveLength(2);
    expect(screen.getByRole("button", { name: "Withdraw" })).toBeTruthy();
    expect(screen.getByText("Disconnect Stripe under Online payments. That ends our access at Stripe.")).toBeTruthy();
    expect(screen.getByText("Invitation for Ana Manager")).toBeTruthy();
    expect(screen.getByText("3 issued invoices each have a link that opens it for anyone who has it, such as your client.")).toBeTruthy();
    expect(screen.getByText(READ_ONLY_NOTE)).toBeTruthy();
    expect(screen.getByRole("link", { name: "Online payments" }).getAttribute("href")).toBe("/settings/payments");
    // Straight to that import on the Import page, where it's cancelled.
    expect(screen.getByRole("link", { name: "Import from Harvest" }).getAttribute("href")).toBe("/settings/import#import-import-7");
    expect(screen.getByRole("link", { name: "Delete the account, under Settings → Account" }).getAttribute("href")).toBe("/settings/account#delete");
  });

  it("lists a Honest Robin Cloud subscription with its plan, billing interval and status, and where to cancel it", async () => {
    const team: Schemas["ConnectionView"] = {
      kind: "subscription",
      plan: "team",
      status: "active",
      interval: "year",
      renews_at: "2027-10-01T00:00:00Z",
      end_in: "/settings/billing",
    };
    renderPage(MoveOutPage, "active", [team]);
    await screen.findByRole("heading", { name: "Moving out changes nothing" });
    expect(screen.getByText("Honest Robin Cloud subscription")).toBeTruthy();
    expect(screen.getByText(`Team plan, paid yearly. Active. It renews on ${formatDate("2027-10-01")}.`)).toBeTruthy();
    expect(
      screen.getByText(
        "Cancel it on the Billing page, in two clicks: it ends with the period you've paid for. Deleting the account cancels it too, on the day the account is deleted for good, 14 days after the deletion is asked for.",
      ),
    ).toBeTruthy();
    expect(screen.getByRole("link", { name: "Billing" }).getAttribute("href")).toBe("/settings/billing");
    expect(screen.getByRole("link", { name: "Cancel the Team plan on the Billing page, in two clicks" }).getAttribute("href")).toBe("/settings/billing");
    cleanup();

    // Cancelled, it ends by itself; while a payment is past due, cancelling ends it at once.
    renderPage(MoveOutPage, "active", [{ ...team, renews_at: undefined, ends_at: "2027-10-01T00:00:00Z" }]);
    await screen.findByRole("heading", { name: "Moving out changes nothing" });
    expect(screen.getByText(`Team plan, paid yearly. Cancelled: it ends on ${formatDate("2027-10-01")}.`)).toBeTruthy();
    expect(screen.getByText(`Nothing to do: it ends by itself on ${formatDate("2027-10-01")}. Until then, Keep the Team plan on the Billing page takes it back.`)).toBeTruthy();
    // Already cancelled, there's nothing to cancel: no link to do it.
    expect(screen.queryByRole("link", { name: "Cancel the Team plan on the Billing page, in two clicks" })).toBeNull();
    cleanup();
    renderPage(MoveOutPage, "active", [{ ...team, interval: "month", status: "past_due", renews_at: undefined }]);
    await screen.findByRole("heading", { name: "Moving out changes nothing" });
    expect(screen.getByText("Team plan, paid monthly. A payment is past due.")).toBeTruthy();
    expect(screen.getByText(/while a payment is past due, that ends it at once/)).toBeTruthy();
  });

  it("waiting to be deleted, the read-only note says deactivating people waits", async () => {
    renderPage(MoveOutPage, "pending_deletion", CONNECTIONS);
    await screen.findByRole("heading", { name: "Moving out changes nothing" });
    expect(screen.getByText(READ_ONLY_NOTE_DELETING)).toBeTruthy();
    expect(screen.queryByText(READ_ONLY_NOTE)).toBeNull();
  });

  it("revoke and withdraw end a token and an invitation while the account is read-only", async () => {
    const del = spyOnDelete();
    renderPage(MoveOutPage, "lapsed", CONNECTIONS);
    await screen.findByRole("heading", { name: "Moving out changes nothing" });

    // Revoke asks first, as the profile does, then ends the token of someone else.
    fireEvent.click(screen.getAllByRole("button", { name: "Revoke" })[1]);
    fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Revoke" }));
    await waitFor(() => expect(del).toHaveBeenCalledWith("/api/v1/account/api_tokens/{id}", { params: { path: { id: "token-2" } } }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

    // Withdraw asks first too, and says that inviting again waits until the account is active.
    fireEvent.click(screen.getByRole("button", { name: "Withdraw" }));
    const dialog = await screen.findByRole("dialog", { name: "Withdraw the invitation for Ana Manager?" });
    expect(within(dialog).getByText("Its link stops working. Inviting them again waits until the account is active again.")).toBeTruthy();
    fireEvent.click(within(dialog).getByRole("button", { name: "Withdraw" }));
    await waitFor(() => expect(del).toHaveBeenCalledWith("/api/v1/people/{id}/invite", { params: { path: { id: "membership-3" } } }));
  });

  it("in an active account the page has the same ways to end each one, without the read-only note", async () => {
    const del = spyOnDelete();
    renderPage(MoveOutPage, "active", CONNECTIONS);
    await screen.findByRole("heading", { name: "Moving out changes nothing" });
    expect(screen.queryByText(READ_ONLY_NOTE)).toBeNull();
    expect(screen.queryByText(READ_ONLY_NOTE_DELETING)).toBeNull();
    expect(screen.getAllByRole("button", { name: "Revoke" })).toHaveLength(2);
    fireEvent.click(screen.getByRole("button", { name: "Withdraw" }));
    const dialog = await screen.findByRole("dialog", { name: "Withdraw the invitation for Ana Manager?" });
    expect(within(dialog).getByText("Its link stops working.")).toBeTruthy();
    fireEvent.click(within(dialog).getByRole("button", { name: "Withdraw" }));
    await waitFor(() => expect(del).toHaveBeenCalledWith("/api/v1/people/{id}/invite", { params: { path: { id: "membership-3" } } }));
  });
});
