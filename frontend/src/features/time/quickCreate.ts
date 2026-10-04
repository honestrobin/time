// SPDX-License-Identifier: AGPL-3.0-only
// "Enter as you go": whatever a time entry still lacks (a client, a project, a task) is created
// from the time dialog, so nobody has to set up the catalog before tracking.

/** Select value for "add a new one". Radix Select can't hold an empty value. */
export const NEW = "__new__";

export interface Named {
  id: string;
  name: string;
}

export interface CatalogTask extends Named {
  is_default: boolean;
  is_active: boolean;
}

export interface WorkInput {
  /** An existing project, or null to create one named `projectName` for the client below. */
  projectId: string | null;
  projectName: string;
  /** For a new project: an existing client, or null to use (or create) one named `clientName`. */
  clientId: string | null;
  clientName: string;
  /** An existing task, or null to use (or create) one named `taskName`; empty means none. */
  taskId: string | null;
  taskName: string;
  /** The existing project's tasks, so a typed name that's already there is simply picked. */
  projectTasks?: { task_id: string; name: string }[];
}

export interface CatalogOps {
  /** Known clients and tasks: names are unique, so a typed name that exists is reused. */
  clients: Named[];
  tasks: CatalogTask[];
  createClient: (name: string) => Promise<Named>;
  createTask: (name: string, isDefault: boolean) => Promise<Named>;
  /** No task ids means the account's default tasks. */
  createProject: (clientId: string, name: string, taskIds: string[] | undefined) => Promise<{ id: string; tasks: { task_id: string }[] }>;
  addTaskToProject: (projectId: string, taskId: string) => Promise<unknown>;
}

const sameName = (a: string, b: string) => a.trim().localeCompare(b.trim(), undefined, { sensitivity: "base" }) === 0;

/** Creates what's missing, in order, and returns the project and task to track to. */
export async function ensureWork(w: WorkInput, ops: CatalogOps): Promise<{ projectId: string; taskId: string | undefined }> {
  let taskId = w.taskId ?? undefined;
  const typedTask = w.taskName.trim();

  if (w.projectId) {
    if (!taskId && typedTask) {
      const onProject = w.projectTasks?.find((t) => sameName(t.name, typedTask));
      if (onProject) return { projectId: w.projectId, taskId: onProject.task_id };
      taskId = await findOrCreateTask(typedTask, ops);
      await ops.addTaskToProject(w.projectId, taskId);
    }
    return { projectId: w.projectId, taskId };
  }

  if (!taskId && typedTask) taskId = await findOrCreateTask(typedTask, ops);
  let clientId = w.clientId ?? undefined;
  if (!clientId) {
    const typedClient = w.clientName.trim();
    clientId = ops.clients.find((c) => sameName(c.name, typedClient))?.id ?? (await ops.createClient(typedClient)).id;
  }
  // A new project gets the account's default tasks, as it would from the projects page, plus
  // the one chosen here.
  const defaults = ops.tasks.filter((t) => t.is_default && t.is_active).map((t) => t.id);
  const taskIds = [...new Set([...defaults, ...(taskId ? [taskId] : [])])];
  const project = await ops.createProject(clientId, w.projectName.trim(), taskIds.length ? taskIds : undefined);
  return { projectId: project.id, taskId: taskId ?? project.tasks[0]?.task_id };
}

async function findOrCreateTask(name: string, ops: CatalogOps): Promise<string> {
  const existing = ops.tasks.find((t) => sameName(t.name, name));
  if (existing) return existing.id;
  // The account's first task becomes a default one, so later projects get it too.
  return (await ops.createTask(name, ops.tasks.length === 0)).id;
}
