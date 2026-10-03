// SPDX-License-Identifier: AGPL-3.0-only
import { useQuery } from "@tanstack/react-query";
import { useMemo } from "react";
import { api, unwrap } from "../../lib/api";
import { usePermissions } from "../../lib/session";
import { useAssignments } from "../time/hooks";
import { activePeopleQuery, byName, clientsQuery, projectsQuery, tasksQuery } from "../projects/queries";
import type { FilterKey } from "./search";

export interface Option {
  id: string;
  name: string;
  /** Shown after the name, e.g. a project's client. */
  hint?: string;
}

/** A project someone can move entries to, with its active tasks. */
export interface ProjectChoice extends Option {
  tasks: Option[];
}

export const teamsQuery = { queryKey: ["teams"], queryFn: () => unwrap(api.GET("/api/v1/teams")) };
export const categoriesQuery = { queryKey: ["expense_categories", "all"], queryFn: () => unwrap(api.GET("/api/v1/expense_categories", { params: { query: {} } })) };

/**
 * What each filter can choose from. Managers and admins pick from the catalog; members only see
 * their own time, so their choices are the projects they're on.
 */
export function useFilterOptions(): { options: Record<FilterKey, Option[]>; projects: ProjectChoice[]; available: FilterKey[] } {
  const perms = usePermissions();
  const manager = perms.isManagerOrAdmin;
  const clients = useQuery({ ...clientsQuery, enabled: manager });
  const projects = useQuery({ ...projectsQuery(), enabled: manager });
  const tasks = useQuery({ ...tasksQuery, enabled: manager });
  const people = useQuery({ ...activePeopleQuery, enabled: manager });
  const teams = useQuery({ ...teamsQuery, enabled: manager });
  const categories = useQuery(categoriesQuery);
  const assignments = useAssignments();

  return useMemo(() => {
    const projectChoices: ProjectChoice[] = manager
      ? (projects.data?.data ?? []).map((p) => ({
          id: p.id,
          name: p.name,
          hint: p.client.name,
          tasks: p.tasks.filter((t) => t.is_active).map((t) => ({ id: t.task_id, name: t.name })),
        }))
      : (assignments.data ?? []).map((a) => ({
          id: a.project_id,
          name: a.project_name,
          hint: a.client.name,
          tasks: a.tasks.map((t) => ({ id: t.task_id, name: t.name })),
        }));
    const myTasks = new Map<string, Option>();
    projectChoices.forEach((p) => p.tasks.forEach((t) => myTasks.set(t.id, t)));
    const options: Record<FilterKey, Option[]> = {
      client: (clients.data?.data ?? []).map((c) => ({ id: c.id, name: c.name, hint: c.currency })).sort(byName),
      project: [...projectChoices].sort(byName),
      task: (manager ? (tasks.data?.data ?? []).map((t) => ({ id: t.id, name: t.name })) : [...myTasks.values()]).sort(byName),
      person: (people.data?.data ?? []).map((p) => ({ id: p.id, name: p.name })).sort(byName),
      team: (teams.data ?? []).map((t) => ({ id: t.id, name: t.name })).sort(byName),
      category: (categories.data ?? []).map((c) => ({ id: c.id, name: c.name })).sort(byName),
    };
    const available: FilterKey[] = manager ? ["client", "project", "task", "person", "team"] : ["project", "task"];
    return { options, projects: projectChoices.filter((p) => p.tasks.length > 0).sort(byName), available };
  }, [manager, clients.data, projects.data, tasks.data, people.data, teams.data, categories.data, assignments.data]);
}
