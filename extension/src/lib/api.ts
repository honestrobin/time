// SPDX-License-Identifier: AGPL-3.0-only
import { ApiError, createHonestRobinClient, unwrap } from "@honestrobin/api-client";
import type { Session } from "./storage";
import type { StartInput, TimeEntry } from "./messages";

export { ApiError, unwrap };

/** A client for the signed-in instance, acting with the extension's token. */
export function clientFor(session: Session) {
  return createHonestRobinClient({ baseUrl: session.instanceUrl, token: () => session.token, accountId: () => session.accountId });
}

export type Client = ReturnType<typeof clientFor>;

/** Today in the person's own time zone, as the web app counts days. */
export function today(now = new Date()): string {
  const p = (n: number) => String(n).padStart(2, "0");
  return `${now.getFullYear()}-${p(now.getMonth() + 1)}-${p(now.getDate())}`;
}

export async function runningTimer(api: Client): Promise<TimeEntry | null> {
  const { data, response } = await api.GET("/api/v1/me/timer");
  if (response.status === 204) return null;
  if (!response.ok) throw new ApiError(response.status, { code: `http_${response.status}`, message: response.statusText });
  return data ?? null;
}

/** Starts a timer (an entry without a duration, like the web app); the API stops any other. */
export function startTimer(api: Client, input: StartInput): Promise<TimeEntry> {
  return unwrap(
    api.POST("/api/v1/time_entries", {
      body: {
        project_id: input.projectId,
        task_id: input.taskId,
        spent_date: today(),
        notes: input.notes || undefined,
        external_reference: input.item
          ? { source: input.item.source, id: input.item.id, group_id: input.item.workspace, url: input.item.url, title: input.item.title }
          : undefined,
      },
    }),
  );
}

export function stopTimer(api: Client, id: string): Promise<TimeEntry> {
  return unwrap(api.POST("/api/v1/time_entries/{id}/stop", { params: { path: { id } } }));
}
