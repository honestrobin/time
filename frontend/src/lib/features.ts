// SPDX-License-Identifier: AGPL-3.0-only
// The parts of Time that are built but switched off by default, while the core is made a joy to
// use first. The server says which are on (`features` in /auth/config, HONESTROBIN_FEATURES);
// the web app shows only those beside the core. The export stays complete; a switched-off part
// also stops its work on the server (see Features.kt).
import { redirect } from "@tanstack/react-router";
import { queryClient } from "./api";
import { authConfigQuery, useAuthConfig } from "./session";

export const FEATURES = ["invoices", "payments", "accounting", "expenses", "tasks", "team", "approvals", "budgets", "import"] as const;
export type Feature = (typeof FEATURES)[number];

/** Whether a feature is switched on. Anything the server doesn't list is off. */
export function featureOn(features: readonly string[] | undefined, feature: Feature): boolean {
  return features?.includes(feature) ?? false;
}

/** The switched-on features, for components. */
export function useFeatures(): readonly string[] {
  return useAuthConfig()?.features ?? [];
}

export function useFeature(feature: Feature): boolean {
  return featureOn(useFeatures(), feature);
}

/**
 * The pages of switched-off features, by route path, as the router guards them (app/router.tsx).
 * Settings pages where a connection ends aren't here: Move out links to them, so they always open.
 */
export const SWITCHED_PAGES: Readonly<Record<string, Feature>> = {
  "/expenses": "expenses",
  "/settings/expense-categories": "expenses",
  "/tasks": "tasks",
  "/team": "team",
  "/team/$personId": "team",
  "/approvals": "approvals",
  "/invoices": "invoices",
  "/invoices/new": "invoices",
  "/invoices/$invoiceId": "invoices",
};

/** Whether [path], such as "/team/abc", is one of the switched pages. */
export function isSwitchedPage(path: string): boolean {
  const bare = path.split(/[?#]/)[0];
  return Object.keys(SWITCHED_PAGES).some((route) => new RegExp(`^${route.replace(/\$[^/]+/g, "[^/]+")}/?$`).test(bare));
}

/** For a route's `beforeLoad`: a page whose feature is switched off goes to Time. */
export function requireFeature(feature: Feature): () => Promise<void> {
  return async () => {
    const config = await queryClient.ensureQueryData(authConfigQuery);
    if (!featureOn(config.features, feature)) throw redirect({ to: "/", replace: true });
  };
}
