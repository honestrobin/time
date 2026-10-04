// SPDX-License-Identifier: MIT
// Typed client for the Honest Robin: Time REST API, generated from openapi.json (see README).
import createClient, { type Middleware } from "openapi-fetch";
import type { components, paths } from "./schema";

export type { components, paths };
export type Schemas = components["schemas"];

export interface ApiErrorBody {
  code: string;
  message: string;
  fields?: Record<string, string>;
  details?: Record<string, unknown>;
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly body: ApiErrorBody,
  ) {
    super(body.message);
  }

  get code() {
    return this.body.code;
  }

  get fields() {
    return this.body.fields ?? {};
  }
}

export interface ClientOptions {
  baseUrl?: string;
  /** Personal access token (extension, scripts). Browsers use the session cookie instead. */
  token?: () => string | null | undefined;
  /** Account to address; sent as the HonestRobin-Account-Id header. */
  accountId?: () => string | null | undefined;
  /** Send the CSRF header from the XSRF-TOKEN cookie (browser sessions). */
  csrf?: boolean;
  fetch?: typeof fetch;
}

function readCookie(name: string): string | undefined {
  if (typeof document === "undefined") return undefined;
  return document.cookie
    .split("; ")
    .find((c) => c.startsWith(`${name}=`))
    ?.slice(name.length + 1);
}

export function createHonestRobinClient(options: ClientOptions = {}) {
  const client = createClient<paths>({
    baseUrl: options.baseUrl ?? "",
    credentials: "include",
    fetch: options.fetch,
  });
  const auth: Middleware = {
    onRequest({ request }) {
      const token = options.token?.();
      if (token) request.headers.set("Authorization", `Bearer ${token}`);
      const account = options.accountId?.();
      if (account) request.headers.set("HonestRobin-Account-Id", account);
      if (options.csrf && request.method !== "GET") {
        const xsrf = readCookie("XSRF-TOKEN");
        if (xsrf) request.headers.set("X-XSRF-TOKEN", decodeURIComponent(xsrf));
      }
      return request;
    },
  };
  client.use(auth);
  return client;
}

export type HonestRobinClient = ReturnType<typeof createHonestRobinClient>;

/** Unwraps an openapi-fetch result, throwing ApiError for non-2xx responses. */
export async function unwrap<T>(
  promise: Promise<{ data?: T; error?: unknown; response: Response }>,
): Promise<T> {
  const { data, error, response } = await promise;
  if (!response.ok) {
    const body = (error as ApiErrorBody | undefined) ?? { code: "http_" + response.status, message: response.statusText };
    throw new ApiError(response.status, typeof body === "object" ? body : { code: "http_" + response.status, message: String(body) });
  }
  return data as T;
}
