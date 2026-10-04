// SPDX-License-Identifier: AGPL-3.0-only
// What the extension keeps, in chrome.storage.local (this device only).
import type { SelectorConfig } from "./sites";

export const CLOUD_URL = "https://time.honestrobin.com";

export interface Session {
  instanceUrl: string;
  token: string;
  accountId: string;
  accountName: string;
  email: string;
  membershipId?: string;
}

export interface Pending {
  instanceUrl: string;
  deviceCode: string;
  userCode: string;
  verificationUri: string;
  interval: number;
  expiresAt: number;
  error?: string;
}

export interface Remembered {
  projectId: string;
  taskId: string;
}

interface Stored {
  session?: Session;
  /** The last instance address typed in, for the next sign-in. */
  instanceUrl?: string;
  selectors?: SelectorConfig;
  pending?: Pending;
  [key: `workspace:${string}`]: Remembered | undefined;
}

async function get<K extends keyof Stored>(key: K): Promise<Stored[K]> {
  const r = await chrome.storage.local.get(key as string);
  return r[key as string] as Stored[K];
}

async function set<K extends keyof Stored>(key: K, value: Stored[K]): Promise<void> {
  await chrome.storage.local.set({ [key]: value });
}

export const storage = {
  session: () => get("session"),
  setSession: (s: Session) => set("session", s),
  clearSession: () => chrome.storage.local.remove("session"),
  instanceUrl: async () => (await get("instanceUrl")) ?? CLOUD_URL,
  setInstanceUrl: (url: string) => set("instanceUrl", url),
  pending: () => get("pending"),
  setPending: (p: Pending) => set("pending", p),
  clearPending: () => chrome.storage.local.remove("pending"),
  selectors: () => get("selectors"),
  setSelectors: (c: SelectorConfig) => set("selectors", c),
  clearSelectors: () => chrome.storage.local.remove("selectors"),
  /** The project and task last used for items of a workspace (spec §9). */
  remembered: (source: string, workspace: string) => get(`workspace:${source}:${workspace}`),
  remember: (source: string, workspace: string, r: Remembered) => set(`workspace:${source}:${workspace}`, r),
};

/**
 * "app.example.com" or "https://app.example.com/" → "https://app.example.com". Plain http only
 * for this computer or a private network: elsewhere anyone on the way could read the token.
 */
export function normaliseInstanceUrl(input: string): string {
  const raw = input.trim().replace(/\/+$/, "");
  const withScheme = /^https?:\/\//i.test(raw) ? raw : `https://${raw}`;
  const url = new URL(withScheme);
  if (url.protocol === "http:" && !privateHost(url.hostname)) {
    throw new Error(`Use https://${url.host}: without https, anyone on the way could read your sign-in.`);
  }
  return url.origin;
}

function privateHost(host: string): boolean {
  if (host === "localhost" || host === "[::1]" || host.endsWith(".local")) return true;
  const ip = host.split(".").map(Number);
  if (ip.length !== 4 || ip.some((n) => !Number.isInteger(n) || n < 0 || n > 255)) return false;
  return ip[0] === 127 || ip[0] === 10 || (ip[0] === 192 && ip[1] === 168) || (ip[0] === 172 && ip[1] >= 16 && ip[1] <= 31);
}
