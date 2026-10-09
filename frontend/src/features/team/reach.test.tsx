// SPDX-License-Identifier: AGPL-3.0-only
// The team is switched off by default, but an account with other people keeps it, so someone can
// always be deactivated, and the people an import brought can be invited again.
import { QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, createRootRoute, createRoute, createRouter, Outlet, RouterProvider } from "@tanstack/react-router";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { queryClient } from "../../lib/api";
import { authConfigQuery, type AuthConfig } from "../../lib/session";
import { requireTeam, teamShows } from "./reach";
import { peopleQuery } from "./shared";

function openTeam(features: string[], people: { id: string; name: string }[]) {
  queryClient.setQueryData(authConfigQuery.queryKey, { edition: "cloud", features } as unknown as AuthConfig);
  queryClient.setQueryData(peopleQuery.queryKey, people as never);
  const root = createRootRoute({ component: Outlet });
  const time = createRoute({ getParentRoute: () => root, path: "/", component: () => <h1>Time</h1> });
  const team = createRoute({ getParentRoute: () => root, path: "/team", component: () => <h1>Team</h1>, beforeLoad: requireTeam });
  const router = createRouter({ routeTree: root.addChildren([time, team]), history: createMemoryHistory({ initialEntries: ["/team"] }) });
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

afterEach(() => {
  cleanup();
  queryClient.clear();
});

const ME = { id: "membership-1", name: "Marta Owner" };
const IVO = { id: "membership-2", name: "Ivo Designer" };

describe("the team", () => {
  it("shows when it's switched on, or when the account has someone besides you", () => {
    expect(teamShows([], [ME])).toBe(false);
    expect(teamShows([], undefined)).toBe(false);
    expect(teamShows(["team"], [ME])).toBe(true);
    expect(teamShows([], [ME, IVO])).toBe(true);
  });

  it("its page goes to Time when it's just you and the team is switched off", async () => {
    openTeam([], [ME]);
    expect(await screen.findByRole("heading", { name: "Time" })).toBeTruthy();
  });

  it("its page opens while there are other people, so they can be deactivated or invited again", async () => {
    openTeam([], [ME, IVO]);
    expect(await screen.findByRole("heading", { name: "Team" })).toBeTruthy();
  });
});
