// SPDX-License-Identifier: AGPL-3.0-only
// Importing Harvest's CSV exports (spec §6.6): upload, check how every column is read, fix the
// mapping, then import. Works without any connection to Harvest.
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, SelectField, TextField } from "../../design";
import { NONE } from "../../design/Select";
import { ApiError, api, authHeaders, errorInfo, unwrap, type Schemas } from "../../lib/api";

type Preview = Schemas["CsvPreview"];
type Input = Schemas["CsvMappingInput"];
type Result = Schemas["CsvResult"];

async function uploadCsv(file: File): Promise<Preview> {
  const body = new FormData();
  body.append("file", file);
  const res = await fetch("/api/v1/imports/csv", { method: "POST", credentials: "include", headers: authHeaders(), body });
  const json = await res.json().catch(() => null);
  if (!res.ok) throw new ApiError(res.status, json && typeof json === "object" && "message" in json ? json : { code: `http_${res.status}`, message: res.statusText });
  return json as Preview;
}

export function CsvImport({ resumeJobId }: { resumeJobId?: string }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [preview, setPreview] = useState<Preview | null>(null);
  const [result, setResult] = useState<Result | null>(null);
  const [emails, setEmails] = useState<Record<string, string>>({});

  const upload = useMutation({ mutationFn: uploadCsv, onSuccess: setPreview });
  const change = useMutation({
    mutationFn: ({ id, input }: { id: string; input: Input }) => unwrap(api.POST("/api/v1/imports/{id}/csv/preview", { params: { path: { id } }, body: input })),
    onSuccess: setPreview,
  });
  const resume = useMutation({
    mutationFn: (id: string) => unwrap(api.POST("/api/v1/imports/{id}/csv/preview", { params: { path: { id } }, body: {} })),
    onSuccess: setPreview,
  });
  const commit = useMutation({
    mutationFn: (p: Preview) =>
      unwrap(api.POST("/api/v1/imports/{id}/csv/commit", { params: { path: { id: p.job_id } }, body: { people: emails, date_order: p.date_order } })),
    onSuccess: (r) => {
      setResult(r);
      void qc.invalidateQueries({ queryKey: ["imports"] });
    },
  });

  const { mutate: resumeJob } = resume;
  useEffect(() => {
    if (resumeJobId) resumeJob(resumeJobId);
  }, [resumeJobId, resumeJob]);

  if (result) {
    const stats = result.stats as Record<string, number>;
    return (
      <div className="stack">
        <p className="notice notice-ok">
          {t("import.csv.done", {
            created: stats.entries_created ?? stats.contacts_created ?? 0,
            updated: stats.entries_updated ?? stats.clients_updated ?? 0,
          })}{" "}
          {t("import.csv.added", { clients: stats.clients ?? 0, projects: stats.projects ?? 0, tasks: stats.tasks ?? 0, people: stats.people ?? 0 })}
        </p>
        {result.skipped > 0 && <p className="notice notice-warn">{t("import.csv.skipped", { count: result.skipped })}</p>}
        <div>
          <Button
            onClick={() => {
              setResult(null);
              setPreview(null);
            }}
          >
            {t("import.csv.another")}
          </Button>
        </div>
      </div>
    );
  }

  if (!preview) {
    const err = upload.error ?? resume.error;
    return (
      <div className="stack">
        <p className="muted">{t("import.csv.lead")}</p>
        <label className="csv-drop">
          <span>{upload.isPending ? t("import.csv.reading") : t("import.csv.choose")}</span>
          <input
            type="file"
            accept=".csv,.txt,text/csv"
            disabled={upload.isPending}
            onChange={(e) => {
              const file = e.target.files?.[0];
              if (file) upload.mutate(file);
              e.target.value = "";
            }}
          />
        </label>
        {err && <p className="notice notice-error">{errorInfo(err).message}</p>}
      </div>
    );
  }

  const p = preview;
  const apply = (input: Input) => change.mutate({ id: p.job_id, input: { kind: p.kind, mapping: p.mapping, date_order: p.date_order, people: emails, ...input } });
  const missing = p.fields.filter((f) => f.required && !p.mapping[f.name]);
  const people = p.creates.people.filter((x) => !x.existing);
  const canImport = missing.length === 0 && p.valid > 0 && !(p.date_order_needed && !p.date_order);
  const columnOptions = [{ value: NONE, label: t("import.csv.notInFile") }, ...p.columns.map((c) => ({ value: c, label: c }))];

  return (
    <div className="stack csv-preview">
      <p>
        <strong>{p.file_name}</strong> · {t("import.csv.rows", { count: p.rows })}
      </p>
      <SelectField label={t("import.csv.kind")} value={p.kind} onChange={(v) => apply({ kind: v as Preview["kind"], mapping: undefined })} options={p.kinds.map((k) => ({ value: k.kind, label: k.label }))} />

      <section>
        <h3>{t("import.csv.columns")}</h3>
        <p className="muted small">{t("import.csv.columnsHint")}</p>
        <div className="form-grid">
          {p.fields.map((f) => (
            <SelectField
              key={f.name}
              label={f.required ? `${f.label} *` : f.label}
              value={p.mapping[f.name] ?? NONE}
              onChange={(v) => apply({ mapping: { ...p.mapping, [f.name]: v === NONE ? undefined : v } as Input["mapping"] })}
              options={columnOptions}
            />
          ))}
        </div>
      </section>

      {p.kind === "TIME" && p.mapping.date && (
        <SelectField
          label={t("import.csv.dateOrder")}
          hint={p.date_order_needed ? t("import.csv.dateOrderNeeded") : undefined}
          value={p.date_order ?? ""}
          placeholder={t("import.csv.chooseOrder")}
          onChange={(v) => apply({ date_order: v as Input["date_order"] })}
          options={[
            { value: "YMD", label: t("import.csv.ymd") },
            { value: "DMY", label: t("import.csv.dmy") },
            { value: "MDY", label: t("import.csv.mdy") },
          ]}
        />
      )}

      {people.length > 0 && (
        <section>
          <h3>{t("import.csv.people")}</h3>
          <p className="muted small">{t("import.csv.peopleHint")}</p>
          <div className="form-grid">
            {people.map((x) => (
              <TextField
                key={x.name}
                label={x.name}
                type="email"
                value={emails[x.name] ?? x.email ?? ""}
                onChange={(v) => setEmails({ ...emails, [x.name]: v })}
                onBlur={() => apply({ people: emails })}
                placeholder={t("import.csv.emailPlaceholder")}
              />
            ))}
          </div>
        </section>
      )}

      {missing.length === 0 && (
        <section>
          <h3>{t("import.csv.check")}</h3>
          <p>
            {t("import.csv.validRows", { valid: p.valid, rows: p.rows })}
            {p.total_hours != null && ` · ${t("import.csv.hours", { hours: p.total_hours })}`}
          </p>
          {(p.creates.clients.length > 0 || p.creates.projects.length > 0 || p.creates.tasks.length > 0 || people.length > 0) && (
            <p className="muted">
              {t("import.csv.willAdd", { clients: p.creates.clients.length, projects: p.creates.projects.length, tasks: p.creates.tasks.length, people: people.length })}
            </p>
          )}
          {p.sample.length > 0 && (
            <div className="table-scroll">
              <table className="ledger">
                <thead>
                  <tr>
                    {Object.keys(p.sample[0]).map((k) => (
                      <th key={k}>{p.fields.find((f) => f.name === k)?.label ?? k}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {p.sample.map((row, i) => (
                    <tr key={i}>
                      {Object.values(row).map((v, j) => (
                        <td key={j}>{v ?? ""}</td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}

      {p.problem_count > 0 && (
        <section>
          <h3>{t("import.csv.problems", { count: p.problem_count })}</h3>
          <p className="muted small">{t("import.csv.problemsHint")}</p>
          <ul className="csv-problems">
            {p.problems.map((x, i) => (
              <li key={i}>
                {x.row > 1 && <span className="muted">{t("import.csv.row", { row: x.row })} </span>}
                {x.message}
              </li>
            ))}
          </ul>
        </section>
      )}

      {commit.error && <p className="notice notice-error">{errorInfo(commit.error).message}</p>}
      <div className="row">
        <Button variant="primary" disabled={!canImport} busy={commit.isPending} onClick={() => commit.mutate(p)}>
          {t("import.csv.import", { count: p.valid })}
        </Button>
        <Button onClick={() => setPreview(null)}>{t("import.csv.otherFile")}</Button>
        {change.isPending && <span className="muted small">{t("import.csv.updating")}</span>}
      </div>
    </div>
  );
}
