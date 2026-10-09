// SPDX-License-Identifier: AGPL-3.0-only
// The parts of Time that are built but switched off by default, while the core is made a joy to
// use first. The server says which are on (`features` in /auth/config, HONESTROBIN_FEATURES);
// the web app shows only those beside the core. The API and the export don't change.
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

/** For a route's `beforeLoad`: a page whose feature is switched off goes to Time. */
export function requireFeature(feature: Feature): () => Promise<void> {
  return async () => {
    const config = await queryClient.ensureQueryData(authConfigQuery);
    if (!featureOn(config.features, feature)) throw redirect({ to: "/", replace: true });
  };
}
