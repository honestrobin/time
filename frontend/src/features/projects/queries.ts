// SPDX-License-Identifier: AGPL-3.0-only
// Queries shared by the projects, clients and tasks pages.
import { queryOptions, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, unwrap, type Schemas } from "../../lib/api";
import type { DurationStyle } from "../../lib/format";
import { accountQuery } from "../settings/AccountSettingsPage";

export type Project = Schemas["ProjectView"];
export type ProjectTask = Schemas["ProjectTaskView"];
export type ProjectMember = Schemas["ProjectMemberView"];
export type Client = Schemas["ClientView"];
export type Contact = Schemas["ContactView"];
export type Task = Schemas["TaskView"];
export type Person = Schemas["PersonView"];
export type Budget = Schemas["BudgetView"];

/** PATCH bodies: the API tells an absent field from null, and null clears the value. */
export type Nullable<T> = { [K in keyof T]?: T[K] | null };

export const projectKeys = {
  all: ["projects"] as const,
  list: (active: boolean | undefined) => ["projects", "list", active ?? "all"] as const,
  detail: (id: string) => ["projects", "detail", id] as const,
  budget: (id: string) => ["projects", "detail", id, "budget"] as const,
};

export const projectsQuery = (isActive?: boolean) =>
  queryOptions({
    queryKey: projectKeys.list(isActive),
    queryFn: () => unwrap(api.GET("/api/v1/projects", { params: { query: { is_active: isActive, limit: 500 } } })),
  });

export const projectQuery = (id: string) =>
  queryOptions({
    queryKey: projectKeys.detail(id),
    queryFn: () => unwrap(api.GET("/api/v1/projects/{id}", { params: { path: { id } } })),
  });

export const budgetQuery = (id: string) =>
  queryOptions({
    queryKey: projectKeys.budget(id),
    queryFn: () => unwrap(api.GET("/api/v1/projects/{id}/budget", { params: { path: { id } } })),
  });

export const clientKeys = { all: ["clients"] as const, list: ["clients", "list"] as const };

export const clientsQuery = queryOptions({
  queryKey: clientKeys.list,
  queryFn: () => unwrap(api.GET("/api/v1/clients", { params: { query: { limit: 500 } } })),
});

export const taskKeys = { all: ["tasks"] as const, list: ["tasks", "list"] as const };

export const tasksQuery = queryOptions({
  queryKey: taskKeys.list,
  queryFn: () => unwrap(api.GET("/api/v1/tasks", { params: { query: { limit: 500 } } })),
});

export const activePeopleQuery = queryOptions({
  queryKey: ["people", "list", { is_active: true, limit: 500 }],
  queryFn: () => unwrap(api.GET("/api/v1/people", { params: { query: { is_active: true, limit: 500 } } })),
});

export { accountQuery };

export const byName = <T extends { name: string }>(a: T, b: T) => a.name.localeCompare(b.name, undefined, { sensitivity: "base" });

/** The account's default currency and duration style, with safe fallbacks while loading. */
export function useAccountDefaults(): { currency: string; durationStyle: DurationStyle } {
  const { data } = useQuery(accountQuery);
  return {
    currency: data?.default_currency ?? "EUR",
    durationStyle: data?.duration_format === "decimal" ? "decimal" : "hm",
  };
}

/** Puts a project returned by a mutation into the cache and refreshes what depends on it. */
export function useProjectCache() {
  const qc = useQueryClient();
  return (p: Project) => {
    qc.setQueryData(projectKeys.detail(p.id), p);
    void qc.invalidateQueries({ queryKey: projectKeys.budget(p.id) });
    void qc.invalidateQueries({ queryKey: ["projects", "list"] });
  };
}
