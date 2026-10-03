// SPDX-License-Identifier: AGPL-3.0-only
// Messages from the content scripts and the popup to the background, which talks to the API
// (content scripts can't: their requests count as the page's, so CORS would stop them).
import type { Schemas } from "@honestrobin/api-client";
import type { Item } from "./sites";

export type TimeEntry = Schemas["TimeEntryView"];
export type Assignment = Schemas["MyAssignment"];

export interface PendingSignIn {
  instanceUrl: string;
  userCode: string;
  verificationUri: string;
  expiresAt: number;
  error?: string;
}

export interface State {
  signedIn: boolean;
  instanceUrl?: string;
  accountName?: string;
  email?: string;
  running: TimeEntry | null;
  /** A sign-in waiting for approval in the web app. */
  pending?: PendingSignIn | null;
}

export interface StartInput {
  projectId: string;
  taskId: string;
  notes: string;
  item?: Item;
}

export type Request =
  | { type: "state" }
  | { type: "assignments" }
  | { type: "remembered"; source: string; workspace: string }
  | { type: "start"; input: StartInput }
  | { type: "stop"; id: string }
  | { type: "refresh" }
  | { type: "recent" }
  | { type: "signIn"; instanceUrl: string; clientName: string }
  | { type: "signInWithToken"; instanceUrl: string; token: string }
  | { type: "cancelSignIn" }
  | { type: "signOut" };

export type Reply<T> = { ok: true; value: T } | { ok: false; error: string; code?: string };

export async function ask<T>(request: Request): Promise<T> {
  const reply = (await chrome.runtime.sendMessage(request)) as Reply<T> | undefined;
  if (!reply) throw new Error("Honest Robin didn't answer. Reload the page and try again.");
  if (!reply.ok) throw Object.assign(new Error(reply.error), { code: reply.code });
  return reply.value;
}
