// SPDX-License-Identifier: AGPL-3.0-only
// A page of a switched-off feature goes to Time; switched on, it opens.
import { createMemoryHistory, createRootRoute, createRoute, createRouter, Outlet, RouterProvider } from "@tanstack/react-router";
import { QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { queryClient } from "./api";
import { featureOn, isSwitchedPage, requireFeature } from "./features";
import { authConfigQuery, type AuthConfig } from "./session";

function openAt(path: string, features: string[]) {
  queryClient.setQueryData(authConfigQuery.queryKey, { edition: "selfhost", features } as unknown as AuthConfig);
  const root = createRootRoute({ component: Outlet });
  const time = createRoute({ getParentRoute: () => root, path: "/", component: () => <h1>Time</h1> });
  const invoices = createRoute({ getParentRoute: () => root, path: "/invoices", component: () => <h1>Invoices</h1>, beforeLoad: requireFeature("invoices") });
  const router = createRouter({ routeTree: root.addChildren([time, invoices]), history: createMemoryHistory({ initialEntries: [path] }) });
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}

afterEach(() => {
  cleanup();
  queryClient.clear();
});

describe("switches", () => {
  it("a page whose feature is switched off goes to Time", async () => {
    const router = openAt("/invoices", []);
    expect(await screen.findByRole("heading", { name: "Time" })).toBeTruthy();
    expect(router.state.location.pathname).toBe("/");
  });

  it("the same page opens when its feature is switched on", async () => {
    openAt("/invoices", ["invoices"]);
    expect(await screen.findByRole("heading", { name: "Invoices" })).toBeTruthy();
  });

  it("knows a switched page by its path, and never a settings page where a connection ends", () => {
    expect(isSwitchedPage("/invoices")).toBe(true);
    expect(isSwitchedPage("/invoices/0192-abc")).toBe(true);
    expect(isSwitchedPage("/team/0192-abc?tab=time")).toBe(true);
    for (const path of ["/", "/reports", "/settings/invoices", "/settings/payments", "/settings/accounting", "/settings/import#import-1", "/settings/billing"]) {
      expect(isSwitchedPage(path), path).toBe(false);
    }
  });

  it("anything the server doesn't list is off", () => {
    expect(featureOn(undefined, "team")).toBe(false);
    expect(featureOn([], "team")).toBe(false);
    expect(featureOn(["team"], "team")).toBe(true);
  });
});
