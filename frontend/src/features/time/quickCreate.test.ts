// SPDX-License-Identifier: AGPL-3.0-only
import { describe, expect, it } from "vitest";
import { ensureWork, type CatalogOps, type CatalogTask, type Named, type WorkInput } from "./quickCreate";

function fakeCatalog(clients: Named[] = [], tasks: CatalogTask[] = []) {
  const calls: string[] = [];
  let n = 0;
  const ops: CatalogOps = {
    clients,
    tasks,
    createClient: async (name) => {
      calls.push(`client ${name}`);
      return { id: `c${++n}`, name };
    },
    createTask: async (name, isDefault) => {
      calls.push(`task ${name}${isDefault ? " (default)" : ""}`);
      return { id: `t${++n}`, name };
    },
    createProject: async (clientId, name, taskIds) => {
      calls.push(`project ${name} for ${clientId} with ${taskIds ? taskIds.join(",") : "defaults"}`);
      return { id: `p${++n}`, tasks: (taskIds ?? []).map((task_id) => ({ task_id })) };
    },
    addTaskToProject: async (projectId, taskId) => {
      calls.push(`add ${taskId} to ${projectId}`);
    },
  };
  return { ops, calls };
}

const work = (w: Partial<WorkInput>): WorkInput => ({ projectId: null, projectName: "", clientId: null, clientName: "", taskId: null, taskName: "", ...w });

describe("ensureWork", () => {
  it("sets up an empty account in one go: client, first task as a default, project", async () => {
    const { ops, calls } = fakeCatalog();
    const result = await ensureWork(work({ projectName: " Website ", clientName: "Acme", taskName: "General" }), ops);
    expect(calls).toEqual(["task General (default)", "client Acme", "project Website for c2 with t1"]);
    expect(result).toEqual({ projectId: "p3", taskId: "t1" });
  });

  it("reuses a client and a task whose names already exist, ignoring case", async () => {
    const { ops, calls } = fakeCatalog([{ id: "c9", name: "Acme" }], [{ id: "t9", name: "Design", is_default: false, is_active: true }]);
    const result = await ensureWork(work({ projectName: "Logo", clientName: "acme", taskName: "design" }), ops);
    expect(calls).toEqual(["project Logo for c9 with t9"]);
    expect(result).toEqual({ projectId: "p1", taskId: "t9" });
  });

  it("gives a new project the account's default tasks too", async () => {
    const tasks = [
      { id: "d1", name: "Meetings", is_default: true, is_active: true },
      { id: "d2", name: "Old", is_default: true, is_active: false },
    ];
    const { ops, calls } = fakeCatalog([], tasks);
    const result = await ensureWork(work({ projectName: "App", clientId: "c1", taskId: null, taskName: "Coding" }), ops);
    expect(calls).toEqual(["task Coding", "project App for c1 with d1,t1"]);
    expect(result.taskId).toBe("t1");
  });

  it("without a typed task, a new project tracks to its first default task", async () => {
    const { ops } = fakeCatalog([], [{ id: "d1", name: "Meetings", is_default: true, is_active: true }]);
    expect(await ensureWork(work({ projectName: "App", clientId: "c1" }), ops)).toEqual({ projectId: "p1", taskId: "d1" });
  });

  it("adds a typed task to an existing project, or picks it if it's already there", async () => {
    const { ops, calls } = fakeCatalog([], []);
    expect(await ensureWork(work({ projectId: "p7", taskName: "Support" }), ops)).toEqual({ projectId: "p7", taskId: "t1" });
    expect(calls).toEqual(["task Support (default)", "add t1 to p7"]);

    const again = fakeCatalog();
    const picked = await ensureWork(work({ projectId: "p7", taskName: "support", projectTasks: [{ task_id: "t5", name: "Support" }] }), again.ops);
    expect(picked).toEqual({ projectId: "p7", taskId: "t5" });
    expect(again.calls).toEqual([]);
  });

  it("creates nothing when everything is chosen", async () => {
    const { ops, calls } = fakeCatalog();
    expect(await ensureWork(work({ projectId: "p1", taskId: "t1" }), ops)).toEqual({ projectId: "p1", taskId: "t1" });
    expect(calls).toEqual([]);
  });
});
