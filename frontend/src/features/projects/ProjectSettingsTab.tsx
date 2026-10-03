// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation, useQuery } from "@tanstack/react-query";
import { useMemo, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { Button, useToast } from "../../design";
import { api, errorInfo, unwrap, type Schemas } from "../../lib/api";
import { usePermissions } from "../../lib/session";
import { formToPatch, ProjectFields, projectToForm, RATE_FIELDS, validateProjectForm, type ProjectFormState } from "./ProjectForm";
import { byName, clientsQuery, useAccountDefaults, useProjectCache, type Project } from "./queries";

/** Edits the same fields as the create form; saves only what changed. */
export function ProjectSettingsTab({ project, onRatesChanged }: { project: Project; onRatesChanged: () => void }) {
  const { t } = useTranslation();
  const toast = useToast();
  const perms = usePermissions();
  const cache = useProjectCache();
  const { durationStyle } = useAccountDefaults();
  const clientsQ = useQuery(clientsQuery);

  const server = useMemo(() => projectToForm(project), [project]);
  const [form, setForm] = useState<ProjectFormState>(server);
  const [base, setBase] = useState(server);
  const [localErrors, setLocalErrors] = useState<Record<string, string>>({});

  // The project changed on the server (saved here, archived, another tab): take the new values
  // for every field the user hasn't touched, keep their edits for the rest.
  if (server !== base) {
    setBase(server);
    setForm((f) => {
      const next = { ...f } as Record<string, unknown>;
      for (const k of Object.keys(server) as (keyof ProjectFormState)[]) if (f[k] === base[k]) next[k] = server[k];
      return next as unknown as ProjectFormState;
    });
  }

  const clients = useMemo(
    () => (clientsQ.data?.data ?? []).filter((c) => c.is_active || c.id === project.client.id).sort(byName),
    [clientsQ.data, project.client.id],
  );
  // Until the client list loads, the project's own client keeps the select from looking empty.
  const clientOptions = clients.length ? clients : [{ ...project.client, is_active: true } as unknown as (typeof clients)[number]];
  const currency = clients.find((c) => c.id === form.client_id)?.currency ?? project.client.currency;

  const patch = formToPatch(server, form, perms.canSeeRates);
  const dirty = Object.keys(patch).length > 0;

  const save = useMutation({
    mutationFn: (body: typeof patch) =>
      unwrap(api.PATCH("/api/v1/projects/{id}", { params: { path: { id: project.id } }, body: body as Schemas["ProjectInput"] })),
    onSuccess: (p, body) => {
      cache(p);
      setForm(projectToForm(p));
      toast(t("app.saved"));
      if (Object.keys(body).some((k) => (RATE_FIELDS as string[]).includes(k))) onRatesChanged();
    },
  });

  const err = save.error ? errorInfo(save.error) : null;
  const errors = { ...(err?.fields ?? {}), ...localErrors };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const local = validateProjectForm(form, t);
    setLocalErrors(local);
    if (Object.keys(local).length || !dirty) return;
    save.mutate(patch);
  };

  return (
    <form onSubmit={submit} noValidate style={{ maxWidth: 720 }}>
      <ProjectFields
        form={form}
        onChange={(p) => setForm((f) => ({ ...f, ...p }))}
        errors={errors}
        clients={clientOptions}
        currency={currency}
        durationStyle={durationStyle}
        mode="edit"
      />
      <div className="form-actions">
        <Button type="submit" variant="primary" busy={save.isPending} disabled={!dirty}>
          {t("app.save")}
        </Button>
        <Button
          variant="ghost"
          disabled={!dirty}
          onClick={() => {
            setForm(server);
            setLocalErrors({});
            save.reset();
          }}
        >
          {t("projects.discardChanges")}
        </Button>
        {(err && !Object.keys(err.fields).length) || Object.keys(errors).length ? (
          <span className="field-error" role="alert">
            {err && !Object.keys(err.fields).length ? err.message : t("projects.form.fixErrors")}
          </span>
        ) : null}
      </div>
    </form>
  );
}
