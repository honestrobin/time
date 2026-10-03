// SPDX-License-Identifier: AGPL-3.0-only
import * as RSelect from "@radix-ui/react-select";
import { useTranslation } from "react-i18next";
import { Field } from "../../design";
import type { Assignment } from "./hooks";

interface Props {
  assignments: Assignment[];
  projectId: string | undefined;
  taskId: string | undefined;
  onChange: (projectId: string, taskId: string | undefined) => void;
  onTaskChange: (taskId: string) => void;
  errors?: { project_id?: string; task_id?: string };
  autoFocus?: boolean;
}

/** Project select grouped by client, then the project's tasks. */
export function ProjectTaskPicker({ assignments, projectId, taskId, onChange, onTaskChange, errors, autoFocus }: Props) {
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
              const tasks = assignments.find((a) => a.project_id === v)?.tasks ?? [];
              onChange(v, tasks.length === 1 ? tasks[0].task_id : undefined);
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
                </RSelect.Viewport>
              </RSelect.Content>
            </RSelect.Portal>
          </RSelect.Root>
        )}
      </Field>
      <Field label={t("time.task")} error={errors?.task_id}>
        {(a11y) => (
          <RSelect.Root value={taskId} onValueChange={onTaskChange} disabled={!project}>
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
                </RSelect.Viewport>
              </RSelect.Content>
            </RSelect.Portal>
          </RSelect.Root>
        )}
      </Field>
    </div>
  );
}
