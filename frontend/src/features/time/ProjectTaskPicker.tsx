// SPDX-License-Identifier: AGPL-3.0-only
import * as RSelect from "@radix-ui/react-select";
import { useTranslation } from "react-i18next";
import { Field } from "../../design";
import type { Assignment } from "./hooks";
import { NEW } from "./quickCreate";

interface Props {
  assignments: Assignment[];
  projectId: string | undefined;
  taskId: string | undefined;
  onChange: (projectId: string, taskId: string | undefined) => void;
  onTaskChange: (taskId: string) => void;
  errors?: { project_id?: string; task_id?: string };
  autoFocus?: boolean;
  /** Offer "New project…" (value NEW) for people who may create projects. */
  onNewProject?: () => void;
  /** Offer "New task…" (value NEW) on the chosen project. */
  onNewTask?: () => void;
  /** While a new project is being entered, its task is chosen below the picker. */
  hideTask?: boolean;
}

/** Project select grouped by client, then the project's tasks. The first task is chosen for you. */
export function ProjectTaskPicker({ assignments, projectId, taskId, onChange, onTaskChange, errors, autoFocus, onNewProject, onNewTask, hideTask }: Props) {
  const { t } = useTranslation();
  const byClient = new Map<string, Assignment[]>();
  assignments.forEach((a) => byClient.set(a.client.name, [...(byClient.get(a.client.name) ?? []), a]));
  const project = assignments.find((a) => a.project_id === projectId);
  return (
    <div className="form-grid">
      <Field label={t("time.project")} error={errors?.project_id}>
        {(a11y) => (
          <RSelect.Root
            value={projectId}
            onValueChange={(v) => {
              if (v === NEW) return onNewProject?.();
              const tasks = assignments.find((a) => a.project_id === v)?.tasks ?? [];
              onChange(v, tasks[0]?.task_id);
            }}
          >
            <RSelect.Trigger className="select-trigger" {...a11y} autoFocus={autoFocus}>
              <RSelect.Value placeholder={t("time.chooseProject")} />
              <RSelect.Icon aria-hidden>▾</RSelect.Icon>
            </RSelect.Trigger>
            <RSelect.Portal>
              <RSelect.Content className="select-content" position="popper" sideOffset={4}>
                <RSelect.Viewport>
                  {[...byClient.entries()].map(([client, list]) => (
                    <RSelect.Group key={client}>
                      <RSelect.Label className="menu-label">{client}</RSelect.Label>
                      {list.map((a) => (
                        <RSelect.Item key={a.project_id} value={a.project_id} className="select-item">
                          <RSelect.ItemText>
                            {a.project_code ? `[${a.project_code}] ` : ""}
                            {a.project_name}
                          </RSelect.ItemText>
                        </RSelect.Item>
                      ))}
                    </RSelect.Group>
                  ))}
                  {onNewProject && (
                    <RSelect.Item value={NEW} className="select-item">
                      <RSelect.ItemText>{t("time.newProject")}</RSelect.ItemText>
                    </RSelect.Item>
                  )}
                </RSelect.Viewport>
              </RSelect.Content>
            </RSelect.Portal>
          </RSelect.Root>
        )}
      </Field>
      {!hideTask && (
        <Field label={t("time.task")} error={errors?.task_id}>
          {(a11y) => (
            <RSelect.Root value={taskId} onValueChange={(v) => (v === NEW ? onNewTask?.() : onTaskChange(v))} disabled={!project}>
              <RSelect.Trigger className="select-trigger" {...a11y}>
                <RSelect.Value placeholder={project ? t("time.chooseTask") : t("time.chooseProjectFirst")} />
                <RSelect.Icon aria-hidden>▾</RSelect.Icon>
              </RSelect.Trigger>
              <RSelect.Portal>
                <RSelect.Content className="select-content" position="popper" sideOffset={4}>
                  <RSelect.Viewport>
                    {(project?.tasks ?? []).map((task) => (
                      <RSelect.Item key={task.task_id} value={task.task_id} className="select-item">
                        <RSelect.ItemText>{task.name}</RSelect.ItemText>
                        {!task.billable && <span className="muted" style={{ marginLeft: "auto", fontSize: "var(--text-sm)" }}>{t("time.nonBillable")}</span>}
                      </RSelect.Item>
                    ))}
                    {onNewTask && project && (
                      <RSelect.Item value={NEW} className="select-item">
                        <RSelect.ItemText>{t("time.newTask")}</RSelect.ItemText>
                      </RSelect.Item>
                    )}
                  </RSelect.Viewport>
                </RSelect.Content>
              </RSelect.Portal>
            </RSelect.Root>
          )}
        </Field>
      )}
    </div>
  );
}
