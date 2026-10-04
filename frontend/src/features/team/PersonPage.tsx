// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useParams } from "@tanstack/react-router";
import { useEffect, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import {
  Button,
  Checkbox,
  ConfirmDialog,
  Dialog,
  DialogActions,
  DurationField,
  EmptyState,
  MoneyField,
  TextField,
  useToast,
} from "../../design";
import { ApiError, api, errorInfo, unwrap } from "../../lib/api";
import { formatDate, formatMoney } from "../../lib/format";
import { useAuthConfig, useMe, usePermissions } from "../../lib/session";
import { useAccountSettings } from "../time/hooks";
import { asRole, byName, personQuery, RoleField, StatusBadge, teamsQuery, useHours, type Person, type Role, type Team } from "./shared";
import "./team.css";

interface Form {
  name: string;
  email: string;
  role: Role;
  weekly_capacity_seconds: number | null;
  is_contractor: boolean;
  is_billable_default: boolean;
  has_access_to_all_future_projects: boolean;
  default_billable_rate: number | null;
  cost_rate: number | null;
  can_see_rates: boolean;
  can_manage_projects: boolean;
  can_manage_invoices: boolean;
  team_ids: string[];
}

function toForm(p: Person): Form {
  return {
    name: p.name,
    email: p.email,
    role: asRole(p.role),
    weekly_capacity_seconds: p.weekly_capacity_seconds,
    is_contractor: p.is_contractor,
    is_billable_default: p.is_billable_default,
    has_access_to_all_future_projects: p.has_access_to_all_future_projects,
    default_billable_rate: p.default_billable_rate ?? null,
    cost_rate: p.cost_rate ?? null,
    can_see_rates: p.can_see_rates,
    can_manage_projects: p.can_manage_projects,
    can_manage_invoices: p.can_manage_invoices,
    team_ids: [...p.team_ids].sort(),
  };
}

/** Permissions an admin can grant per role; admins have all of them implicitly. */
const PERMISSIONS: Record<Role, ("can_see_rates" | "can_manage_projects" | "can_manage_invoices")[]> = {
  admin: [],
  manager: ["can_see_rates", "can_manage_projects", "can_manage_invoices"],
  member: ["can_see_rates"],
};

/** Only the fields that changed; a cleared rate is sent as null. */
function changes(form: Form, original: Form, withRates: boolean): Record<string, unknown> {
  const body: Record<string, unknown> = {};
  const simple = ["name", "email", "role", "weekly_capacity_seconds", "is_contractor", "is_billable_default", "has_access_to_all_future_projects"] as const;
  for (const k of simple) if (form[k] !== original[k] && !(k === "weekly_capacity_seconds" && form[k] === null)) body[k] = form[k];
  if (withRates) {
    if (form.default_billable_rate !== original.default_billable_rate) body.default_billable_rate = form.default_billable_rate;
    if (form.cost_rate !== original.cost_rate) body.cost_rate = form.cost_rate;
  }
  for (const k of PERMISSIONS[form.role]) if (form[k] !== original[k]) body[k] = form[k];
  if (form.team_ids.join() !== original.team_ids.join()) body.team_ids = form.team_ids;
  return body;
}

export function PersonPage() {
  const { t } = useTranslation();
  const { personId } = useParams({ strict: false }) as { personId: string };
  const perms = usePermissions();
  const q = useQuery({ ...personQuery(personId), retry: false });
  const person = q.data;

  if (q.isError) {
    const notFound = q.error instanceof ApiError && (q.error.status === 404 || q.error.status === 403);
    return (
      <div className="page page-narrow">
        <BackLink />
        <EmptyState
          title={notFound ? t("team.notFound") : errorInfo(q.error).message}
          action={
            <Link to="/team" className="btn btn-secondary">
              {t("team.backToTeam")}
            </Link>
          }
        />
      </div>
    );
  }
  if (!person) return <div className="page page-narrow">{t("app.loading")}</div>;

  return (
    <div className="page page-narrow person-page">
      <BackLink />
      <header className="page-header">
        <div>
          <h1>{person.name}</h1>
          <p className="muted">{person.email}</p>
          <div className="person-badges">
            <span className="badge">{t(`team.roles.${asRole(person.role)}`)}</span>
            {person.is_contractor && <span className="badge">{t("team.contractor")}</span>}
            <StatusBadge person={person} showInactive />
          </div>
        </div>
      </header>
      {perms.isAdmin ? <EditPerson person={person} /> : <ReadOnlyPerson person={person} />}
    </div>
  );
}

function BackLink() {
  const { t } = useTranslation();
  return (
    <Link to="/team" className="person-back">
      {t("team.backToTeam")}
    </Link>
  );
}

/* ---- Invitation ---------------------------------------------------------------------------- */

function InvitationStrip({ person }: { person: Person }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const toast = useToast();
  const emailWorks = useAuthConfig()?.email_configured !== false;
  const invite = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/people/{id}/invite", { params: { path: { id: person.id } } })),
    onSuccess: (p) => {
      qc.setQueryData(personQuery(person.id).queryKey, p);
      void qc.invalidateQueries({ queryKey: ["people", "all"] });
      toast(t(emailWorks ? "team.invitationSent" : "team.invitationInLog"));
    },
  });
  if (person.status === "active" || !person.is_active) return null;
  return (
    <div className="notice invite-strip">
      <span className="spacer">
        {person.status === "invited" && person.invited_at
          ? t("team.invitedOn", { date: formatDate(person.invited_at) })
          : t("team.notInvitedYet")}
        {invite.error && <span className="field-error" style={{ display: "block" }}>{errorInfo(invite.error).message}</span>}
      </span>
      <Button size="sm" busy={invite.isPending} onClick={() => invite.mutate()}>
        {person.status === "invited" ? t("team.resendInvitation") : t("team.sendInvitation")}
      </Button>
    </div>
  );
}

/* ---- Admin edit form ----------------------------------------------------------------------- */

function EditPerson({ person }: { person: Person }) {
  const { t } = useTranslation();
  const perms = usePermissions();
  const me = useMe();
  const account = useAccountSettings();
  const qc = useQueryClient();
  const toast = useToast();
  const teams = useQuery(teamsQuery);
  const original = toForm(person);
  const [form, setForm] = useState<Form>(original);
  const [staleCount, setStaleCount] = useState<number | null>(null);

  // Reset the form when another person is opened or after a save updates the record.
  useEffect(() => {
    setForm(toForm(person));
  }, [person.id, person.updated_at]);

  const set = <K extends keyof Form>(k: K) => (v: Form[K]) => setForm((f) => ({ ...f, [k]: v }));
  const body = changes(form, original, perms.canSeeRates);
  const dirty = Object.keys(body).length > 0;

  const save = useMutation({
    mutationFn: (b: Record<string, unknown>) => unwrap(api.PATCH("/api/v1/people/{id}", { params: { path: { id: person.id } }, body: b as never })),
    onSuccess: async (p, b) => {
      qc.setQueryData(personQuery(person.id).queryKey, p);
      void qc.invalidateQueries({ queryKey: ["people", "all"] });
      void qc.invalidateQueries({ queryKey: ["teams"] });
      if (p.id === me.current_membership_id) void qc.invalidateQueries({ queryKey: ["me"] });
      toast(t("app.saved"));
      if ("default_billable_rate" in b || "cost_rate" in b) {
        try {
          const stale = await unwrap(api.GET("/api/v1/rates/stale", { params: { query: { membership_id: person.id } } }));
          if (stale.entries > 0) {
            applyRates.reset();
            setStaleCount(stale.entries);
          }
        } catch {
          // The change itself was saved; failing to count stale entries isn't worth interrupting for.
        }
      }
    },
  });
  const applyRates = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/rates/apply", { body: { membership_id: person.id } })),
    onSuccess: (res) => {
      setStaleCount(null);
      toast(t("team.ratesUpdated", { count: res.entries }));
      // Entry amounts changed everywhere: timesheets, budgets, reports.
      void qc.invalidateQueries();
    },
  });

  const err = save.error ? errorInfo(save.error) : null;
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (dirty) save.mutate(body);
  };
  const signedIn = !!person.user_id;
  const n = staleCount ?? 0;

  return (
    <>
      <InvitationStrip person={person} />
      <form onSubmit={submit}>
        {err && !Object.keys(err.fields).length && (
          <p className="notice notice-error" role="alert" style={{ marginBottom: 16 }}>
            {err.message}
          </p>
        )}
        <section>
          <h2 style={{ marginBottom: 12 }}>{t("team.details")}</h2>
          <div className="form-grid">
            <TextField label={t("team.name")} value={form.name} onChange={set("name")} error={err?.fields.name} required />
            <TextField
              label={t("team.email")}
              type="email"
              value={form.email}
              onChange={set("email")}
              error={err?.fields.email}
              hint={signedIn ? t("team.emailLocked") : undefined}
              readOnly={signedIn}
              required
            />
            <RoleField value={form.role} onChange={set("role")} error={err?.fields.role} />
            <DurationField
              label={t("team.capacity")}
              hint={t("team.capacityHint")}
              value={form.weekly_capacity_seconds}
              onChange={set("weekly_capacity_seconds")}
              error={err?.fields.weekly_capacity_seconds}
            />
          </div>
          <div className="stack" style={{ marginTop: 16 }}>
            <Checkbox checked={form.is_contractor} onChange={set("is_contractor")} label={t("team.contractor")} hint={t("team.contractorHint")} />
            <Checkbox
              checked={form.is_billable_default}
              onChange={set("is_billable_default")}
              label={t("team.billableDefault")}
              hint={t("team.billableDefaultHint")}
            />
            <Checkbox
              checked={form.has_access_to_all_future_projects}
              onChange={set("has_access_to_all_future_projects")}
              label={t("team.futureProjects")}
              hint={t("team.futureProjectsHint")}
            />
          </div>
        </section>

        {perms.canSeeRates && (
          <section className="form-section">
            <h2>{t("team.rates")}</h2>
            <p className="muted">{t("team.ratesLead")}</p>
            <div className="form-grid">
              <MoneyField
                label={t("team.billableRate")}
                hint={t("team.billableRateHint")}
                value={form.default_billable_rate}
                onChange={set("default_billable_rate")}
                currency={account.currency}
                error={err?.fields.default_billable_rate}
              />
              <MoneyField
                label={t("team.costRate")}
                hint={t("team.costRateHint")}
                value={form.cost_rate}
                onChange={set("cost_rate")}
                currency={account.currency}
                error={err?.fields.cost_rate}
              />
            </div>
          </section>
        )}

        <section className="form-section">
          <h2>{t("team.permissions")}</h2>
          {form.role === "admin" ? (
            <p className="muted">{t("team.adminPermissions")}</p>
          ) : (
            <>
              <p className="muted">{form.role === "manager" ? t("team.managerPermissionsLead") : t("team.memberPermissionsLead")}</p>
              <div className="stack">
                <Checkbox checked={form.can_see_rates} onChange={set("can_see_rates")} label={t("team.canSeeRates")} hint={t("team.canSeeRatesHint")} />
                {form.role === "manager" && (
                  <>
                    <Checkbox
                      checked={form.can_manage_projects}
                      onChange={set("can_manage_projects")}
                      label={t("team.canManageProjects")}
                      hint={t("team.canManageProjectsHint")}
                    />
                    <Checkbox
                      checked={form.can_manage_invoices}
                      onChange={set("can_manage_invoices")}
                      label={t("team.canManageInvoices")}
                      hint={t("team.canManageInvoicesHint")}
                    />
                  </>
                )}
              </div>
            </>
          )}
        </section>

        <section className="form-section">
          <h2>{t("team.teams")}</h2>
          <p className="muted">{t("team.personTeamsLead")}</p>
          <TeamChecklist teams={teams.data ?? []} loading={teams.isLoading} value={form.team_ids} onChange={set("team_ids")} />
          {err?.fields.team_ids && <p className="field-error">{err.fields.team_ids}</p>}
        </section>

        <div className="row" style={{ marginTop: 24 }}>
          <Button type="submit" variant="primary" busy={save.isPending} disabled={!dirty}>
            {t("app.save")}
          </Button>
          {dirty && (
            <Button variant="ghost" onClick={() => setForm(original)}>
              {t("team.discardChanges")}
            </Button>
          )}
        </div>
      </form>

      <AccessSection person={person} />

      <Dialog open={staleCount !== null} onOpenChange={(o) => !o && setStaleCount(null)} title={t("team.staleTitle")}>
        <div className="stack">
          <p>{t("team.staleBody", { count: n })}</p>
          <p className="muted">{t("team.staleKept")}</p>
          {applyRates.error && <p className="notice notice-error">{errorInfo(applyRates.error).message}</p>}
        </div>
        <DialogActions>
          <Button onClick={() => setStaleCount(null)}>{t("team.staleKeep")}</Button>
          <Button variant="primary" busy={applyRates.isPending} onClick={() => applyRates.mutate()}>
            {t("team.staleApply", { count: n })}
          </Button>
        </DialogActions>
      </Dialog>
    </>
  );
}

function TeamChecklist({ teams, loading, value, onChange }: { teams: Team[]; loading: boolean; value: string[]; onChange: (ids: string[]) => void }) {
  const { t } = useTranslation();
  if (loading) return <p className="muted">{t("app.loading")}</p>;
  if (teams.length === 0) {
    return (
      <p className="muted">
        {t("team.teamsNone")} <Link to="/team">{t("team.goToTeam")}</Link>
      </p>
    );
  }
  return (
    <div className="checklist" role="group" aria-label={t("team.teams")}>
      {[...teams].sort(byName).map((tm) => (
        <Checkbox
          key={tm.id}
          checked={value.includes(tm.id)}
          onChange={(on) => onChange((on ? [...value, tm.id] : value.filter((x) => x !== tm.id)).sort())}
          label={tm.name}
        />
      ))}
    </div>
  );
}

function AccessSection({ person }: { person: Person }) {
  const { t } = useTranslation();
  const me = useMe();
  const qc = useQueryClient();
  const toast = useToast();
  const [confirming, setConfirming] = useState(false);
  const toggle = useMutation({
    mutationFn: (isActive: boolean) =>
      unwrap(api.PATCH("/api/v1/people/{id}", { params: { path: { id: person.id } }, body: { is_active: isActive } })),
    onSuccess: (p) => {
      qc.setQueryData(personQuery(person.id).queryKey, p);
      void qc.invalidateQueries({ queryKey: ["people"] });
      setConfirming(false);
      toast(p.is_active ? t("team.reactivated", { name: p.name }) : t("team.deactivated", { name: p.name }));
    },
    onError: () => setConfirming(false),
  });
  const self = person.id === me.current_membership_id;
  const err = toggle.error ? errorInfo(toggle.error) : null;

  return (
    <section className="form-section" style={{ marginTop: 32 }}>
      <h2>{t("team.access")}</h2>
      {person.is_active ? (
        <>
          <p className="muted">{t("team.deactivateLead")}</p>
          {self ? (
            <p className="notice">{t("team.cantDeactivateSelf")}</p>
          ) : (
            <Button variant="danger" onClick={() => setConfirming(true)}>
              {t("team.deactivateName", { name: person.name })}
            </Button>
          )}
        </>
      ) : (
        <>
          <p className="muted">{t("team.reactivateLead", { name: person.name })}</p>
          <Button busy={toggle.isPending} onClick={() => toggle.mutate(true)}>
            {t("team.reactivate")}
          </Button>
        </>
      )}
      {err && (
        <p className="field-error" role="alert" style={{ marginTop: 8 }}>
          {err.fields.is_active ?? err.message}
        </p>
      )}
      <ConfirmDialog
        open={confirming}
        onOpenChange={setConfirming}
        title={t("team.deactivateTitle", { name: person.name })}
        body={t("team.deactivateBody", { name: person.name })}
        confirmLabel={t("team.deactivate")}
        busy={toggle.isPending}
        onConfirm={() => toggle.mutate(false)}
      />
    </section>
  );
}

/* ---- Read-only view for managers --------------------------------------------------------- */

function ReadOnlyPerson({ person }: { person: Person }) {
  const { t } = useTranslation();
  const perms = usePermissions();
  const account = useAccountSettings();
  const hours = useHours();
  const teams = useQuery(teamsQuery);
  const teamNames = (teams.data ?? [])
    .filter((tm) => person.team_ids.includes(tm.id))
    .map((tm) => tm.name)
    .sort();
  const yesNo = (v: boolean) => (v ? t("app.yes") : t("app.no"));
  const rate = (v: number | undefined) => (v === undefined || v === null ? t("team.noRate") : `${formatMoney(v, account.currency)} ${t("team.perHourShort")}`);
  const role = asRole(person.role);
  const granted = PERMISSIONS[role].filter((k) => person[k]);

  return (
    <>
      <p className="notice" style={{ marginBottom: 24 }}>
        {t("team.readOnly")}
      </p>
      <dl className="facts">
        <dt>{t("team.email")}</dt>
        <dd>{person.email}</dd>
        <dt>{t("team.role")}</dt>
        <dd>
          {t(`team.roles.${role}`)} <span className="muted">{t(`team.roleHints.${role}`)}</span>
        </dd>
        <dt>{t("team.capacity")}</dt>
        <dd>{hours(person.weekly_capacity_seconds)}</dd>
        <dt>{t("team.contractor")}</dt>
        <dd>{yesNo(person.is_contractor)}</dd>
        <dt>{t("team.billableDefault")}</dt>
        <dd>{yesNo(person.is_billable_default)}</dd>
        <dt>{t("team.futureProjects")}</dt>
        <dd>{yesNo(person.has_access_to_all_future_projects)}</dd>
        {perms.canSeeRates && (
          <>
            <dt>{t("team.billableRate")}</dt>
            <dd>{rate(person.default_billable_rate)}</dd>
            <dt>{t("team.costRate")}</dt>
            <dd>{rate(person.cost_rate)}</dd>
          </>
        )}
        <dt>{t("team.permissions")}</dt>
        <dd>
          {role === "admin"
            ? t("team.adminPermissions")
            : granted.length
              ? granted.map((k) => t(`team.${k === "can_see_rates" ? "canSeeRates" : k === "can_manage_projects" ? "canManageProjects" : "canManageInvoices"}`)).join(", ")
              : t("app.none")}
        </dd>
        <dt>{t("team.teams")}</dt>
        <dd>{teamNames.length ? teamNames.join(", ") : <span className="muted">{t("team.notInAnyTeam")}</span>}</dd>
      </dl>
    </>
  );
}
