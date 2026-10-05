// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { Button, Checkbox, PageHeader, TextField } from "../../design";
import { CsvImport } from "./CsvImport";
import { api, errorInfo, unwrap, type Schemas } from "../../lib/api";
import { formatDateTime, formatDuration, formatMoney, formatMonth } from "../../lib/format";
import { usePermissions } from "../../lib/session";
import { useAccountDefaults } from "../projects/queries";
import "./import.css";

type Job = Schemas["ImportJobView"];
interface Progress {
  phase?: string | null;
  step?: string | null;
  done?: string[];
  counts?: Record<string, number>;
}
interface Row {
  client: string;
  project: string;
  month: string;
  currency: string;
  entries: number;
  hours: number;
  harvest_hours: number | null;
  billable_amount: number;
  harvest_billable_amount: number | null;
  status: "match" | "rounding" | "mismatch";
}
interface InvoiceCheck {
  client: string;
  currency: string;
  count: number;
  harvest_count: number;
  total: number;
  harvest_total: number;
  status: "match" | "mismatch";
}
interface Verification {
  entries: number;
  invoices: number;
  projects: number;
  all_match: boolean;
  mismatches: number;
  rows: Row[];
  invoice_checks: InvoiceCheck[];
}

/** The steps of a run, grouped as the spec's phases (§6.3), in the order the importer runs them. */
const PHASES: { id: string; steps: string[] }[] = [
  { id: "structure", steps: ["company", "users", "roles", "clients", "contacts", "tasks", "projects", "task_assignments", "user_assignments", "expense_categories"] },
  { id: "recent", steps: ["recent_time_entries", "recent_expenses", "open_invoices"] },
  { id: "history", steps: ["history_time_entries", "history_expenses", "invoices", "estimates", "finish"] },
  { id: "verification", steps: ["verification"] },
];

/** A date [days] from today, in the person's time zone, as a date input wants it. */
function localDate(days: number) {
  const d = new Date();
  d.setDate(d.getDate() + days);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

const active = (j?: Job) => j?.status === "queued" || j?.status === "running";

export function ImportPage() {
  const { t } = useTranslation();
  const perms = usePermissions();
  const jobs = useQuery({
    queryKey: ["imports"],
    queryFn: () => unwrap(api.GET("/api/v1/imports")),
    enabled: perms.isAdmin,
    refetchInterval: (q) => (active(q.state.data?.[0]) ? 1500 : q.state.data?.[0]?.status === "syncing" ? 60_000 : false),
  });
  const latest = jobs.data?.[0];

  if (!perms.isAdmin) {
    return (
      <div className="page page-narrow">
        <PageHeader title={t("import.title")} />
        <p className="notice">{t("import.adminOnly")}</p>
      </div>
    );
  }

  const csv = latest?.mode === "csv";
  return (
    <div className="page page-narrow import-page">
      <PageHeader title={t("import.title")} lead={t("import.lead")} />
      {csv && latest.status === "preview" ? (
        <CsvImport resumeJobId={latest.id} />
      ) : csv && latest.status === "completed" ? (
        <CsvJobView job={latest} />
      ) : latest && latest.status !== "cancelled" ? (
        <JobView job={latest} />
      ) : (
        <Start />
      )}
      {latest && !active(latest) && latest.status !== "failed" && latest.status !== "syncing" && !(csv && latest.status === "preview") && (
        <details className="import-again">
          <summary>{t("import.again")}</summary>
          <p className="muted">{t("import.againLead")}</p>
          <Start />
        </details>
      )}
    </div>
  );
}

/** Two ways in: Harvest's API (everything, with verification), or its CSV exports (offline). */
function Start() {
  const { t } = useTranslation();
  const [way, setWay] = useState<"api" | "csv">("api");
  return (
    <div className="stack">
      <div className="tabs" role="tablist" aria-label={t("import.how")}>
        <button type="button" role="tab" className="tab" aria-selected={way === "api"} data-state={way === "api" ? "active" : "inactive"} onClick={() => setWay("api")}>
          {t("import.viaApi")}
        </button>
        <button type="button" role="tab" className="tab" aria-selected={way === "csv"} data-state={way === "csv" ? "active" : "inactive"} onClick={() => setWay("csv")}>
          {t("import.viaCsv")}
        </button>
      </div>
      {way === "api" ? <StartForm /> : <CsvImport />}
    </div>
  );
}

/** A finished CSV import: what it wrote, and the rows it skipped. */
function CsvJobView({ job }: { job: Job }) {
  const { t } = useTranslation();
  const stats = (job.stats ?? {}) as Record<string, number>;
  return (
    <div className="stack">
      <p className="notice notice-ok">
        {t("import.csv.done", { created: stats.entries_created ?? stats.contacts_created ?? 0, updated: stats.entries_updated ?? stats.clients_updated ?? 0 })}{" "}
        {t("import.csv.added", { clients: stats.clients ?? 0, projects: stats.projects ?? 0, tasks: stats.tasks ?? 0, people: stats.people ?? 0 })}
      </p>
      {(stats.skipped ?? 0) > 0 && <Issues jobId={job.id} />}
      <p className="muted small">{t("import.finishedAt", { date: job.finished_at ? formatDateTime(job.finished_at) : "—" })}</p>
    </div>
  );
}

function StartForm() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [token, setToken] = useState("");
  const [accountId, setAccountId] = useState("");
  const [receipts, setReceipts] = useState(true);
  const [sync, setSync] = useState(false);
  const [syncUntil, setSyncUntil] = useState(() => localDate(14));
  const start = useMutation({
    mutationFn: () =>
      unwrap(api.POST("/api/v1/imports/harvest", { body: { token, account_id: accountId, import_receipts: receipts, sync_until: sync ? syncUntil : undefined } })),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["imports"] }),
  });
  const err = start.error ? errorInfo(start.error) : null;
  const submit = (e: FormEvent) => {
    e.preventDefault();
    start.mutate();
  };
  return (
    <form className="stack" onSubmit={submit}>
      <ol className="import-steps">
        <li>
          {t("import.step1")}{" "}
          <a href="https://id.getharvest.com/developers" target="_blank" rel="noreferrer">
            {t("import.step1Link")}
          </a>
        </li>
        <li>{t("import.step2")}</li>
      </ol>
      <div className="form-grid">
        <TextField label={t("import.token")} value={token} onChange={setToken} error={err?.fields.token} autoComplete="off" required fieldClassName="span-2" />
        <TextField label={t("import.accountId")} hint={t("import.accountIdHint")} value={accountId} onChange={setAccountId} error={err?.fields.account_id} inputMode="numeric" autoComplete="off" required />
      </div>
      <Checkbox checked={receipts} onChange={setReceipts} label={t("import.receipts")} hint={t("import.receiptsHint")} />
      <Checkbox checked={sync} onChange={setSync} label={t("import.syncOption")} hint={t("import.syncOptionHint")} />
      {sync && (
        <TextField
          label={t("import.syncUntil")}
          type="date"
          value={syncUntil}
          onChange={setSyncUntil}
          min={localDate(0)}
          max={localDate(30)}
          error={err?.fields.sync_until}
          fieldClassName="sync-until"
        />
      )}
      <p className="muted">{t("import.safe")}</p>
      {err && !Object.keys(err.fields).length && <p className="notice notice-error">{err.message}</p>}
      <div>
        <Button type="submit" variant="primary" busy={start.isPending}>
          {t("import.start")}
        </Button>
      </div>
    </form>
  );
}

function JobView({ job }: { job: Job }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const progress = (job.progress ?? {}) as Progress;
  const done = new Set(progress.done ?? []);
  const counts = progress.counts ?? {};
  const refresh = () => qc.invalidateQueries({ queryKey: ["imports"] });
  const resume = useMutation({ mutationFn: () => unwrap(api.POST("/api/v1/imports/{id}/resume", { params: { path: { id: job.id } } })), onSuccess: refresh });
  const cancel = useMutation({ mutationFn: () => unwrap(api.POST("/api/v1/imports/{id}/cancel", { params: { path: { id: job.id } } })), onSuccess: refresh });
  const syncNow = useMutation({ mutationFn: () => unwrap(api.POST("/api/v1/imports/{id}/sync", { params: { path: { id: job.id } } })), onSuccess: refresh });
  const stopSync = useMutation({ mutationFn: () => unwrap(api.POST("/api/v1/imports/{id}/stop_sync", { params: { path: { id: job.id } } })), onSuccess: refresh });
  const finished = job.status === "completed" || job.status === "syncing";
  const verification = job.verification as Verification | null | undefined;

  const firstOpen = PHASES.find((p) => p.steps.some((s) => !done.has(s)))?.id;
  const stateOf = (p: (typeof PHASES)[number]) => {
    if (finished || p.steps.every((s) => done.has(s))) return "done";
    if (p.id !== firstOpen) return "waiting";
    return job.status === "failed" ? "stopped" : "current";
  };

  return (
    <div className="stack">
      {job.status === "syncing" && (
        <div className="notice notice-ok stack">
          <p>
            <strong>{t("import.syncingTitle")}</strong> {t("import.syncing", { until: job.sync_until ? formatDateTime(job.sync_until) : "—" })}{" "}
            {job.last_synced_at && t("import.lastSynced", { date: formatDateTime(job.last_synced_at) })}
          </p>
          {job.error && <p className="muted">{job.error}</p>}
          <div className="row">
            <Button busy={syncNow.isPending} onClick={() => syncNow.mutate()}>
              {t("import.syncNow")}
            </Button>
            <Button variant="primary" busy={stopSync.isPending} onClick={() => stopSync.mutate()}>
              {t("import.stopSync")}
            </Button>
          </div>
          <p className="muted small">{t("import.syncRules")}</p>
        </div>
      )}
      {finished && verification && (
        <p className={verification.all_match ? "notice notice-ok" : "notice notice-warn"}>
          <strong>{t("import.summary", {
              entries: t("import.n.entries", { count: verification.entries }),
              invoices: t("import.n.invoices", { count: verification.invoices }),
              projects: t("import.n.projects", { count: verification.projects }),
            })}</strong>{" "}
          {verification.all_match ? t("import.allMatch") : t("import.someDiffer", { count: verification.mismatches })}
        </p>
      )}
      {job.status === "failed" && (
        <div className="notice notice-error">
          <p>{job.error}</p>
          {job.can_resume && (
            <div className="row" style={{ marginTop: 8 }}>
              <Button variant="primary" busy={resume.isPending} onClick={() => resume.mutate()}>
                {t("import.resume")}
              </Button>
              {/* A stopped import keeps its Harvest token until it's resumed or cancelled. */}
              <Button variant="ghost" busy={cancel.isPending} onClick={() => cancel.mutate()}>
                {t("import.cancel")}
              </Button>
            </div>
          )}
        </div>
      )}

      <ol className="import-phases" aria-live="polite">
        {PHASES.map((p) => {
          const state = stateOf(p);
          return (
            <li key={p.id} className={`phase phase-${state}`}>
              <span className="phase-mark" aria-hidden="true">{state === "done" ? "✓" : state === "current" ? "…" : state === "stopped" ? "!" : ""}</span>
              <div>
                <div className="phase-title">
                  {t(`import.phases.${p.id}`)} <span className="muted">{t(`import.phaseState.${state}`)}</span>
                </div>
                <div className="muted phase-counts">
                  {p.steps
                    .filter((s) => (counts[s] ?? 0) > 0 && s !== "company" && s !== "finish" && s !== "verification")
                    .map((s) => t(`import.counts.${s}`, { count: counts[s] }))
                    .join(" · ")}
                </div>
              </div>
            </li>
          );
        })}
      </ol>

      {active(job) && (
        <div className="row">
          <span className="muted">{t("import.running")}</span>
          <Button variant="ghost" busy={cancel.isPending} onClick={() => cancel.mutate()}>
            {t("import.cancel")}
          </Button>
        </div>
      )}

      {job.status === "completed" && (
        <ul className="import-next">
          <li>{t("import.nextInvite")}</li>
          <li>
            {t("import.nextRevoke")}{" "}
            <a href="https://id.getharvest.com/developers" target="_blank" rel="noreferrer">
              {t("import.step1Link")}
            </a>
          </li>
        </ul>
      )}

      {verification && <VerificationTables verification={verification} />}
      {!active(job) && job.issue_count > 0 && <Issues jobId={job.id} />}
      <p className="muted small">
        {t("import.startedAt", { date: job.started_at ? formatDateTime(job.started_at) : "—" })}
        {job.finished_at && ` · ${t("import.finishedAt", { date: formatDateTime(job.finished_at) })}`}
      </p>
    </div>
  );
}

function VerificationTables({ verification }: { verification: Verification }) {
  const { t } = useTranslation();
  const { durationStyle } = useAccountDefaults();
  const [onlyDiffs, setOnlyDiffs] = useState(!verification.all_match);
  const rows = onlyDiffs ? verification.rows.filter((r) => r.status === "mismatch") : verification.rows;
  const hours = (h: number | null) => (h === null ? "—" : formatDuration(Math.round(h * 3600), durationStyle));
  return (
    <section className="stack">
      <div className="row" style={{ justifyContent: "space-between" }}>
        <h2>{t("import.verification")}</h2>
        <Checkbox checked={onlyDiffs} onChange={setOnlyDiffs} label={t("import.onlyDiffs")} />
      </div>
      <p className="muted">{t("import.verificationLead")}</p>
      <div className="table-scroll">
        <table className="ledger">
          <thead>
            <tr>
              <th>{t("import.col.project")}</th>
              <th>{t("import.col.month")}</th>
              <th className="num">{t("import.col.hours")}</th>
              <th className="num">{t("import.col.harvestHours")}</th>
              <th className="num">{t("import.col.billable")}</th>
              <th className="num">{t("import.col.harvestBillable")}</th>
              <th>{t("import.col.status")}</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && (
              <tr>
                <td colSpan={7} className="muted">
                  {t("import.noDiffs")}
                </td>
              </tr>
            )}
            {rows.map((r) => (
              <tr key={`${r.client}|${r.project}|${r.month}`}>
                <td>
                  {r.project} <span className="muted">({r.client})</span>
                </td>
                <td className="nowrap">{formatMonth(r.month)}</td>
                <td className="num">{hours(r.hours)}</td>
                <td className="num">{hours(r.harvest_hours)}</td>
                <td className="num">{formatMoney(r.billable_amount, r.currency)}</td>
                <td className="num">{r.harvest_billable_amount === null ? "—" : formatMoney(r.harvest_billable_amount, r.currency)}</td>
                <td>
                  <span className={r.status === "mismatch" ? "badge badge-warn" : "badge badge-ok"} title={r.status === "rounding" ? t("import.roundingHint") : undefined}>
                    {t(`import.status.${r.status}`)}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {verification.invoice_checks.length > 0 && (
        <div className="table-scroll">
          <table className="ledger">
            <thead>
              <tr>
                <th>{t("import.col.client")}</th>
                <th className="num">{t("import.col.invoices")}</th>
                <th className="num">{t("import.col.harvestInvoices")}</th>
                <th className="num">{t("import.col.total")}</th>
                <th className="num">{t("import.col.harvestTotal")}</th>
                <th>{t("import.col.status")}</th>
              </tr>
            </thead>
            <tbody>
              {verification.invoice_checks.map((c) => (
                <tr key={`${c.client}|${c.currency}`}>
                  <td>{c.client}</td>
                  <td className="num">{c.count}</td>
                  <td className="num">{c.harvest_count}</td>
                  <td className="num">{formatMoney(c.total, c.currency)}</td>
                  <td className="num">{formatMoney(c.harvest_total, c.currency)}</td>
                  <td>
                    <span className={c.status === "match" ? "badge badge-ok" : "badge badge-warn"}>{t(`import.status.${c.status}`)}</span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function Issues({ jobId }: { jobId: string }) {
  const { t } = useTranslation();
  const issues = useQuery({ queryKey: ["import_issues", jobId], queryFn: () => unwrap(api.GET("/api/v1/imports/{id}/issues", { params: { path: { id: jobId } } })) });
  const list = issues.data ?? [];
  return (
    <details className="import-issues">
      <summary>{t("import.issues", { count: list.length })}</summary>
      <table className="ledger">
        <thead>
          <tr>
            <th>{t("import.col.what")}</th>
            <th>{t("import.col.reason")}</th>
          </tr>
        </thead>
        <tbody>
          {list.map((i) => (
            <tr key={i.id}>
              <td>
                <span className={i.severity === "error" ? "badge badge-warn" : "badge"}>{t(`import.severity.${i.severity}`)}</span> {t(`import.entity.${i.entity_type}`, { defaultValue: i.entity_type })}{" "}
                {i.external_id && <span className="muted">#{i.external_id}</span>}
              </td>
              <td>{i.reason}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </details>
  );
}
