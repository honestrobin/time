// SPDX-License-Identifier: AGPL-3.0-only
// The background: answers the popup and the content scripts, keeps the toolbar badge current, and
// fetches newer selector configs from the instance.
import bundled from "./selectors.json";
import { ApiError, clientFor, runningTimer, startTimer, stopTimer, today, unwrap } from "./lib/api";
import type { Reply, Request, State, TimeEntry } from "./lib/messages";
import { newer, type SelectorConfig } from "./lib/sites";
import { normaliseInstanceUrl, storage, type Session } from "./lib/storage";

const BADGE_COLOR = "#1F5C45";

async function state(): Promise<State> {
  const session = await storage.session();
  if (!session) {
    const p = await storage.pending();
    const pending = p && p.expiresAt > Date.now() ? { instanceUrl: p.instanceUrl, userCode: p.userCode, verificationUri: p.verificationUri, expiresAt: p.expiresAt, error: p.error } : null;
    return { signedIn: false, running: null, pending };
  }
  const running = await runningTimer(clientFor(session));
  await showBadge(running);
  return { signedIn: true, instanceUrl: session.instanceUrl, accountName: session.accountName, email: session.email, running };
}

// ---- Signing in (RFC 8628 device flow, approved in the web app) ----

async function post<T>(url: string, body: unknown): Promise<{ status: number; json: T }> {
  const res = await fetch(url, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body), credentials: "omit" });
  return { status: res.status, json: (await res.json().catch(() => ({}))) as T };
}

async function signIn(instanceInput: string, clientName: string) {
  const instanceUrl = normaliseInstanceUrl(instanceInput);
  await storage.setInstanceUrl(instanceUrl);
  const { status, json } = await post<{ device_code: string; user_code: string; verification_uri_complete: string; expires_in: number; interval: number; message?: string }>(
    `${instanceUrl}/api/v1/auth/device`,
    { client_name: clientName },
  );
  if (status !== 200) throw new Error(json.message ?? `${instanceUrl} doesn't look like Honest Robin (answered ${status}).`);
  // The page to approve the code must be on the instance itself, never somewhere it points to.
  if (new URL(json.verification_uri_complete).origin !== instanceUrl) throw new Error(`${instanceUrl} sent a sign-in page somewhere else; not opening it.`);
  await storage.setPending({
    instanceUrl,
    deviceCode: json.device_code,
    userCode: json.user_code,
    verificationUri: json.verification_uri_complete,
    interval: json.interval,
    expiresAt: Date.now() + json.expires_in * 1000,
  });
  await chrome.tabs.create({ url: json.verification_uri_complete });
  void poll();
  return state();
}

let polling = false;

/** Asks for the token every few seconds until approved, declined or expired. Each round touches storage, which keeps the worker alive. */
async function poll() {
  if (polling) return;
  polling = true;
  try {
    for (;;) {
      const p = await storage.pending();
      if (!p || p.error || p.expiresAt < Date.now()) return;
      await new Promise((r) => setTimeout(r, Math.max(1, p.interval) * 1000));
      const { status, json } = await post<{ token?: string; account_id?: string; account_name?: string; email?: string; code?: string; message?: string }>(
        `${p.instanceUrl}/api/v1/auth/device/token`,
        { device_code: p.deviceCode },
      );
      if (status === 200 && json.token) {
        await finishSignIn({ instanceUrl: p.instanceUrl, token: json.token, accountId: json.account_id!, accountName: json.account_name ?? "", email: json.email ?? "" });
        return;
      }
      if (json.code !== "authorization_pending") {
        await storage.setPending({ ...p, error: json.message ?? "Signing in didn't work. Try again." });
        return;
      }
      await storage.setPending(p);
    }
  } finally {
    polling = false;
  }
}

async function finishSignIn(session: Session) {
  const me = await unwrap(clientFor(session).GET("/api/v1/me"));
  await storage.setSession({ ...session, membershipId: me.current_membership_id ?? undefined, email: session.email || me.email });
  await storage.clearPending();
  // A config from an instance applies only while signed in to it.
  await storage.clearSelectors();
  void refreshSelectors();
  await state();
}

async function signInWithToken(instanceInput: string, token: string) {
  const instanceUrl = normaliseInstanceUrl(instanceInput);
  await storage.setInstanceUrl(instanceUrl);
  const api = clientFor({ instanceUrl, token: token.trim(), accountId: "", accountName: "", email: "" });
  const me = await unwrap(api.GET("/api/v1/me"));
  const account = me.accounts.find((a) => a.id === me.current_account_id) ?? me.accounts[0];
  if (!account) throw new Error("This token has no account.");
  await finishSignIn({ instanceUrl, token: token.trim(), accountId: account.id, accountName: account.name, email: me.email });
  return state();
}

/** The person's last few distinct entries, to start again with one click. */
async function recent(): Promise<TimeEntry[]> {
  const session = await signedIn();
  const from = new Date(Date.now() - 14 * 86_400_000);
  const page = await unwrap(
    clientFor(session).GET("/api/v1/time_entries", {
      params: { query: { from: today(from), to: today(), membership_id: session.membershipId, limit: 50 } },
    }),
  );
  const seen = new Set<string>();
  return page.data.filter((e) => {
    const k = `${e.project.id}|${e.task.id}|${e.notes ?? ""}`;
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  }).slice(0, 5);
}

/** The badge shows how long the timer has run (h:mm), or nothing. */
async function showBadge(running: TimeEntry | null) {
  if (!running?.timer_started_at) {
    await chrome.action.setBadgeText({ text: "" });
    return;
  }
  const seconds = (running.duration_seconds ?? 0) + Math.max(0, (Date.now() - Date.parse(running.timer_started_at)) / 1000);
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  await chrome.action.setBadgeBackgroundColor({ color: BADGE_COLOR });
  await chrome.action.setBadgeText({ text: `${h}:${String(m).padStart(2, "0")}` });
}

async function handle(request: Request): Promise<unknown> {
  switch (request.type) {
    case "state":
    case "refresh":
      return state();
    case "assignments": {
      const session = await signedIn();
      return unwrap(clientFor(session).GET("/api/v1/me/assignments"));
    }
    case "remembered":
      return (await storage.remembered(request.source, request.workspace)) ?? null;
    case "start": {
      const session = await signedIn();
      const entry = await startTimer(clientFor(session), request.input);
      if (request.input.item) {
        await storage.remember(request.input.item.source, request.input.item.workspace, { projectId: request.input.projectId, taskId: request.input.taskId });
      }
      await showBadge(entry);
      return entry;
    }
    case "stop": {
      const entry = await stopTimer(clientFor(await signedIn()), request.id);
      await showBadge(null);
      return entry;
    }
    case "recent":
      return recent();
    case "signIn":
      return signIn(request.instanceUrl, request.clientName);
    case "signInWithToken":
      return signInWithToken(request.instanceUrl, request.token);
    case "cancelSignIn":
      await storage.clearPending();
      return state();
    case "signOut":
      await storage.clearSession();
      await storage.clearSelectors();
      await chrome.action.setBadgeText({ text: "" });
      return state();
  }
}

async function signedIn() {
  const session = await storage.session();
  if (!session) throw Object.assign(new Error("Sign in to Honest Robin first: click the Honest Robin button in your browser's toolbar."), { code: "signed_out" });
  return session;
}

/** What a script in a web page (the "Track time" button) may ask for; the rest is for the popup. */
const FROM_PAGES = new Set<Request["type"]>(["state", "refresh", "assignments", "remembered", "start", "stop"]);

chrome.runtime.onMessage.addListener((request: Request, sender, respond: (r: Reply<unknown>) => void) => {
  if (sender.id !== chrome.runtime.id) return false;
  // Content scripts run inside other sites' pages; a page that took over its renderer could send
  // anything they can. So signing in, out or with a token, and the recent list, need one of the
  // extension's own pages (the popup, also when it's opened in a tab).
  const ownPage = sender.url?.startsWith(chrome.runtime.getURL("")) === true;
  if (!ownPage && !FROM_PAGES.has(request.type)) {
    respond({ ok: false, error: "Use the Honest Robin button in your browser's toolbar for this." });
    return false;
  }
  handle(request).then(
    (value) => respond({ ok: true, value }),
    async (e: unknown) => {
      // A revoked or expired token: forget it, so the popup offers to sign in again.
      if (e instanceof ApiError && e.status === 401) await storage.clearSession();
      respond({ ok: false, error: e instanceof Error ? e.message : String(e), code: e instanceof ApiError ? e.code : (e as { code?: string })?.code });
    },
  );
  return true; // answers asynchronously
});

/**
 * A newer selector config from the instance the person signed in to, so changes to a site can be
 * fixed without a store release. Nothing is fetched before signing in. A config that isn't newer
 * than the bundled one, or not safe to use (sites.ts, `newer`), is dropped.
 */
async function refreshSelectors() {
  const session = await storage.session();
  if (!session) return;
  try {
    const res = await fetch(`${session.instanceUrl}/extension/selectors.json`, { credentials: "omit" });
    if (!res.ok) return;
    const remote = (await res.json()) as SelectorConfig;
    const accepted = newer(bundled as SelectorConfig, remote);
    if (accepted === bundled) await storage.clearSelectors();
    else await storage.setSelectors(accepted);
  } catch {
    // Offline or an older instance: what's stored stays.
  }
}

chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === "badge") void state().catch(() => undefined);
  if (alarm.name === "selectors") void refreshSelectors();
});

function schedule() {
  void chrome.alarms.create("badge", { periodInMinutes: 1 });
  void chrome.alarms.create("selectors", { periodInMinutes: 24 * 60, delayInMinutes: 1 });
}

chrome.runtime.onInstalled.addListener(() => {
  schedule();
  void refreshSelectors();
});
chrome.runtime.onStartup.addListener(() => {
  schedule();
  void state().catch(() => undefined);
});

// A sign-in left waiting when the worker stopped carries on.
void poll();
