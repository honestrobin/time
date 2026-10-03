// SPDX-License-Identifier: AGPL-3.0-only
import { ApiError, createHonestRobinClient, unwrap, type Schemas } from "@honestrobin/api-client";
import { QueryClient, QueryCache, MutationCache } from "@tanstack/react-query";
import { confirmIdentity } from "./reauth";

export { ApiError, unwrap };
export type { Schemas };

const ACCOUNT_KEY = "honestrobin.account";
let currentAccountId: string | null = localStorage.getItem(ACCOUNT_KEY);

export const api = createHonestRobinClient({ csrf: true, accountId: () => currentAccountId });

// When an action needs a recent sign-in, ask for the password and send the request again.
const unsent = new WeakMap<Request, Request>();
api.use({
  onRequest({ request }) {
    if (request.method !== "GET") unsent.set(request, request.clone());
    return request;
  },
  async onResponse({ request, response }) {
    if (response.status !== 403) return response;
    const body = (await response.clone().json().catch(() => null)) as { code?: string } | null;
    const copy = unsent.get(request);
    if (body?.code !== "reauth_required" || !copy || !(await confirmIdentity())) return response;
    const xsrf = readCookie("XSRF-TOKEN");
    if (xsrf) copy.headers.set("X-XSRF-TOKEN", decodeURIComponent(xsrf));
    return fetch(copy);
  },
});

export function getAccountId() {
  return currentAccountId;
}

export function setAccountId(id: string | null) {
  currentAccountId = id;
  if (id) localStorage.setItem(ACCOUNT_KEY, id);
  else localStorage.removeItem(ACCOUNT_KEY);
}

function onUnauthenticated(error: unknown) {
  if (error instanceof ApiError && error.status === 401 && !location.pathname.startsWith("/login")) {
    const next = location.pathname + location.search;
    location.assign(`/login?next=${encodeURIComponent(next)}`);
  }
}

export const queryClient = new QueryClient({
  queryCache: new QueryCache({ onError: onUnauthenticated }),
  mutationCache: new MutationCache({ onError: onUnauthenticated }),
  defaultOptions: {
    queries: {
      staleTime: 15_000,
      retry: (count, error) => !(error instanceof ApiError && error.status < 500) && count < 2,
      refetchOnWindowFocus: true,
    },
  },
});

/** Reads the error message and field errors from anything thrown by an API call. */
export function errorInfo(error: unknown): { message: string; fields: Record<string, string>; code?: string } {
  if (error instanceof ApiError) return { message: error.message, fields: error.fields, code: error.code };
  if (error instanceof Error) return { message: error.message, fields: {} };
  return { message: String(error), fields: {} };
}

function readCookie(name: string): string | undefined {
  return document.cookie
    .split("; ")
    .find((c) => c.startsWith(`${name}=`))
    ?.slice(name.length + 1);
}

/** Headers the API needs on a hand-made request: the CSRF token and the account. */
export function authHeaders(): Record<string, string> {
  const headers: Record<string, string> = {};
  const xsrf = readCookie("XSRF-TOKEN");
  if (xsrf) headers["X-XSRF-TOKEN"] = decodeURIComponent(xsrf);
  if (currentAccountId) headers["HonestRobin-Account-Id"] = currentAccountId;
  return headers;
}

/**
 * Fetches a file the API serves (an invoice PDF) with the session's headers. With a filename it is
 * downloaded; without one it opens in a tab that was opened synchronously, so pop-up blockers allow it.
 */
export async function openFile(path: string, filename?: string): Promise<void> {
  const tab = filename ? null : window.open("about:blank", "_blank");
  const res = await fetch(path, { credentials: "include", headers: authHeaders() });
  if (!res.ok) {
    tab?.close();
    throw new ApiError(res.status, { code: `http_${res.status}`, message: res.statusText });
  }
  const url = URL.createObjectURL(await res.blob());
  if (tab) {
    tab.location.href = url;
  } else {
    const a = document.createElement("a");
    a.href = url;
    a.download = filename!;
    a.click();
  }
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

/** Downloads a file the API generates (a report export) under the name the server gives it. */
export async function downloadFile(path: string): Promise<void> {
  const res = await fetch(path, { credentials: "include", headers: authHeaders() });
  if (!res.ok) {
    const body = await res.json().catch(() => ({ code: `http_${res.status}`, message: res.statusText }));
    throw new ApiError(res.status, body);
  }
  const disposition = res.headers.get("Content-Disposition") ?? "";
  const filename = /filename\*=UTF-8''([^;]+)/i.exec(disposition)?.[1] ?? /filename="?([^";]+)"?/i.exec(disposition)?.[1] ?? "export";
  const url = URL.createObjectURL(await res.blob());
  const a = document.createElement("a");
  a.href = url;
  a.download = decodeURIComponent(filename);
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}
