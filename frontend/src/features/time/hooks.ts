// SPDX-License-Identifier: AGPL-3.0-only
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { api, unwrap, type Schemas } from "../../lib/api";
import { todayIn } from "../../lib/dates";
import type { DurationStyle } from "../../lib/format";

export type TimeEntry = Schemas["TimeEntryView"];
export type Assignment = Schemas["MyAssignment"];
export type Week = Schemas["WeekView"];

export function useAccountSettings() {
  const { data } = useQuery({ queryKey: ["account"], queryFn: () => unwrap(api.GET("/api/v1/account")), staleTime: 60_000 });
  return {
    loaded: !!data,
    timezone: data?.timezone ?? "UTC",
    weekStart: data?.week_start ?? 1,
    durationStyle: (data?.duration_format ?? "hm") as DurationStyle,
    approvalsEnabled: data?.approvals_enabled ?? false,
    currency: data?.default_currency ?? "EUR",
    today: todayIn(data?.timezone ?? "UTC"),
  };
}

export function useAssignments() {
  return useQuery({ queryKey: ["me", "assignments"], queryFn: () => unwrap(api.GET("/api/v1/me/assignments")), staleTime: 60_000 });
}

export const timerQuery = {
  queryKey: ["timer"],
  queryFn: async (): Promise<TimeEntry | null> => {
    const { data, response } = await api.GET("/api/v1/me/timer");
    if (response.status === 204) return null;
    if (!response.ok) throw new Error(response.statusText);
    return data ?? null;
  },
  refetchInterval: 30_000,
};

export function useRunningTimer() {
  const q = useQuery(timerQuery);
  return { entry: q.data ?? null, fetchedAt: q.dataUpdatedAt };
}

/** Everything that shows time depends on these queries; refresh them after any change. */
export function useInvalidateTime() {
  const qc = useQueryClient();
  return () =>
    Promise.all([
      qc.invalidateQueries({ queryKey: ["time_entries"] }),
      qc.invalidateQueries({ queryKey: ["week"] }),
      qc.invalidateQueries({ queryKey: ["timer"] }),
    ]);
}

/** Current time, re-rendering every second while [active]. Server time is the source of truth. */
export function useNow(active: boolean) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!active) return;
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, [active]);
  return now;
}

/**
 * Live duration for a running entry. The server's duration already includes elapsed time up to
 * the moment it answered, so we add the time since the data was fetched (immune to client clock skew).
 */
export function liveSeconds(entry: TimeEntry, now: number, fetchedAt: number): number {
  if (!entry.is_running) return entry.duration_seconds;
  return Math.min(86_400, entry.duration_seconds + Math.max(0, Math.floor((now - fetchedAt) / 1000)));
}
