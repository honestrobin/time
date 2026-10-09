// SPDX-License-Identifier: AGPL-3.0-only
// The team is switched off by default (lib/features), but an account with other people keeps it:
// deactivating someone, which is the way back for an account that turned read-only, and inviting
// again the people an imported export brought, must always be possible.
import { useQuery } from "@tanstack/react-query";
import { redirect } from "@tanstack/react-router";
import { queryClient } from "../../lib/api";
import { featureOn, useFeatures } from "../../lib/features";
import { authConfigQuery, usePermissions } from "../../lib/session";
import { peopleQuery } from "./shared";

/** Whether the team shows: switched on, or the account has someone besides you. */
export function teamShows(features: readonly string[] | undefined, people: readonly unknown[] | undefined): boolean {
  return featureOn(features, "team") || (people?.length ?? 0) > 1;
}

/** For the menu, the billing page and Move out. Only managers and admins see the team. */
export function useTeamShows(): boolean {
  const features = useFeatures();
  const perms = usePermissions();
  const people = useQuery({ ...peopleQuery, enabled: perms.isManagerOrAdmin && !featureOn(features, "team") });
  return teamShows(features, people.data);
}

/** For the Team pages' `beforeLoad`: without a team to show, they go to Time. */
export async function requireTeam(): Promise<void> {
  const config = await queryClient.ensureQueryData(authConfigQuery);
  if (featureOn(config.features, "team")) return;
  const people = await queryClient.ensureQueryData(peopleQuery).catch(() => undefined);
  if (!teamShows(config.features, people)) throw redirect({ to: "/", replace: true });
}
