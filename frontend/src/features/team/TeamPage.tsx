// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "@tanstack/react-router";
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
  LoadingRow,
  MoneyField,
  PageHeader,
  Tabs,
  TextField,
  useToast,
} from "../../design";
import { api, errorInfo, unwrap } from "../../lib/api";
import { useAuthConfig, useMe, usePermissions } from "../../lib/session";
import { useAccountSettings } from "../time/hooks";
import { fetchInviteLink, InviteLinkDialog, type InviteLink } from "./InviteLink";
import { asRole, byName, peopleQuery, RoleField, StatusBadge, teamsQuery, useHours, type Person, type Role, type Team } from "./shared";
import "./team.css";

export function TeamPage() {
  const { t } = useTranslation();
  const perms = usePermissions();
  const me = useMe();
  const navigate = useNavigate();
  const hours = useHours();
  const people = useQuery(peopleQuery);
  const teams = useQuery(teamsQuery);
  const [filter, setFilter] = useState<"active" | "deactivated">("active");
  const [inviting, setInviting] = useState(false);

  const all = [...(people.data ?? [])].sort(byName);
  const active = all.filter((p) => p.is_active);
  const deactivated = all.filter((p) => !p.is_active);
  const shown = filter === "active" ? active : deactivated;
  const teamNames = new Map((teams.data ?? []).map((tm) => [tm.id, tm.name]));

  return (
    <div className="page">
      <PageHeader
        title={t("team.title")}
        lead={t("team.lead")}
        actions={
          perms.isAdmin && (
            <Button variant="primary" onClick={() => setInviting(true)}>
              {t("team.invite")}
            </Button>
          )
        }
      />

      <Tabs
        value={filter}
        onChange={(v) => setFilter(v as "active" | "deactivated")}
        tabs={[
          { value: "active", label: t("team.filterActive", { count: active.length }) },
          { value: "deactivated", label: t("team.filterDeactivated", { count: deactivated.length }) },
        ]}
      />

      {people.isError ? (
        <p className="notice notice-error" role="alert">
          {errorInfo(people.error).message}
        </p>
      ) : !people.isLoading && shown.length === 0 ? (
        <EmptyState
          title={filter === "active" ? t("team.noActiveTitle") : t("team.noDeactivatedTitle")}
          body={filter === "active" ? t("team.noActive") : t("team.noDeactivated")}
          action={
            filter === "active" &&
            perms.isAdmin && (
              <Button variant="primary" onClick={() => setInviting(true)}>
                {t("team.invite")}
              </Button>
            )
          }
        />
      ) : (
        <table className="ledger">
          <thead>
            <tr>
              <th>{t("team.name")}</th>
              <th>{t("team.email")}</th>
              <th>{t("team.role")}</th>
              <th>{t("team.status")}</th>
              <th className="num">{t("team.capacity")}</th>
              <th>{t("team.teams")}</th>
            </tr>
          </thead>
          <tbody>
            {people.isLoading && <LoadingRow colSpan={6} />}
            {shown.map((p) => (
              <tr key={p.id} className="is-clickable" onClick={() => void navigate({ to: "/team/$personId", params: { personId: p.id } })}>
                <td>
                  <Link to="/team/$personId" params={{ personId: p.id }} onClick={(e) => e.stopPropagation()}>
                    {p.name}
                  </Link>
                  {p.id === me.current_membership_id && <span className="muted"> ({t("team.you")})</span>}
                  {p.two_factor_enabled && (
                    <span className="badge" title={t("team.twoFactorOn")} style={{ marginLeft: 8 }}>
                      {t("team.twoFactorBadge")}
                    </span>
                  )}
                </td>
                <td className="muted">{p.email}</td>
                <td>
                  {t(`team.roles.${asRole(p.role)}`)}
                  {p.is_contractor && <span className="muted">, {t("team.contractorShort")}</span>}
                </td>
                <td>
                  <StatusBadge person={p} />
                </td>
                <td className="num">{hours(p.weekly_capacity_seconds)}</td>
                <td className="muted">
                  {p.team_ids
                    .map((id) => teamNames.get(id))
                    .filter(Boolean)
                    .sort()
                    .join(", ")}
                </td>
              </tr>
            ))}
          </tbody>
          {shown.length > 0 && (
            <tfoot>
              <tr>
                <td colSpan={4} className="muted">
                  {t("team.peopleCount", { count: shown.length })}
                </td>
                <td className="num total">{hours(shown.reduce((s, p) => s + p.weekly_capacity_seconds, 0))}</td>
                <td />
              </tr>
            </tfoot>
          )}
        </table>
      )}

      <TeamsSection teams={teams.data ?? []} loading={teams.isLoading} people={active} />

      <InviteDialog open={inviting} onOpenChange={setInviting} />
    </div>
  );
}

/* ---- Invite ------------------------------------------------------------------------------ */

interface InviteForm {
  name: string;
  email: string;
  role: Role;
  capacity: number | null;
  billableRate: number | null;
  costRate: number | null;
  futureProjects: boolean;
  sendInvite: boolean;
}

const emptyInvite: InviteForm = {
  name: "",
  email: "",
  role: "member",
  capacity: 40 * 3600,
  billableRate: null,
  costRate: null,
  futureProjects: false,
  sendInvite: true,
};

function InviteDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const { t } = useTranslation();
  const perms = usePermissions();
  const account = useAccountSettings();
  const qc = useQueryClient();
  const toast = useToast();
  const emailWorks = useAuthConfig()?.email_configured !== false;
  const [form, setForm] = useState<InviteForm>(emptyInvite);
  const [link, setLink] = useState<InviteLink | null>(null);
  const set = <K extends keyof InviteForm>(k: K) => (v: InviteForm[K]) => setForm((f) => ({ ...f, [k]: v }));
  // Without email the invitation can't be sent, so the admin gets its link to pass on instead.
  const handOver = form.sendInvite && !emailWorks;

  const create = useMutation({
    mutationFn: async () => {
      const person = await unwrap(
        api.POST("/api/v1/people", {
          body: {
            name: form.name,
            email: form.email,
            role: form.role,
            weekly_capacity_seconds: form.capacity ?? 0,
            has_access_to_all_future_projects: form.futureProjects,
            send_invite: form.sendInvite && emailWorks,
            ...(perms.canSeeRates
              ? { default_billable_rate: form.billableRate ?? undefined, cost_rate: form.costRate ?? undefined }
              : {}),
          },
        }),
      );
      return { person, link: handOver ? await fetchInviteLink(person.id, person.name) : null };
    },
    onSuccess: ({ person, link }) => {
      void qc.invalidateQueries({ queryKey: ["people"] });
      if (link) setLink(link);
      else toast(form.sendInvite ? t("team.invitationSent") : t("team.personAdded", { name: person.name }));
      onOpenChange(false);
    },
  });

  useEffect(() => {
    if (open) {
      setForm(emptyInvite);
      create.reset();
    }
  }, [open]);

  const err = create.error ? errorInfo(create.error) : null;
  const submit = (e: FormEvent) => {
    e.preventDefault();
    create.mutate();
  };

  return (
    <>
      <Dialog open={open} onOpenChange={onOpenChange} title={t("team.inviteTitle")}>
      <form className="stack" onSubmit={submit}>
        {err && !Object.keys(err.fields).length && (
          <p className="notice notice-error" role="alert">
            {err.message}
          </p>
        )}
        <div className="form-grid">
          <TextField label={t("team.name")} value={form.name} onChange={set("name")} error={err?.fields.name} autoComplete="off" autoFocus required />
          <TextField label={t("team.email")} type="email" value={form.email} onChange={set("email")} error={err?.fields.email} autoComplete="off" required />
          <RoleField value={form.role} onChange={set("role")} error={err?.fields.role} />
          <DurationField
            label={t("team.capacity")}
            hint={t("team.capacityHint")}
            value={form.capacity}
            onChange={set("capacity")}
            error={err?.fields.weekly_capacity_seconds}
          />
          {perms.canSeeRates && (
            <>
              <MoneyField
                label={t("team.billableRate")}
                hint={t("team.perHour")}
                value={form.billableRate}
                onChange={set("billableRate")}
                currency={account.currency}
                error={err?.fields.default_billable_rate}
              />
              <MoneyField
                label={t("team.costRate")}
                hint={t("team.perHour")}
                value={form.costRate}
                onChange={set("costRate")}
                currency={account.currency}
                error={err?.fields.cost_rate}
              />
            </>
          )}
        </div>
        <Checkbox checked={form.futureProjects} onChange={set("futureProjects")} label={t("team.futureProjects")} hint={t("team.futureProjectsHint")} />
        <Checkbox checked={form.sendInvite} onChange={set("sendInvite")} label={t("team.sendInvite")} hint={t(emailWorks ? "team.sendInviteHint" : "team.sendInviteHintNoEmail")} />
        <DialogActions>
          <Button onClick={() => onOpenChange(false)}>{t("app.cancel")}</Button>
          <Button type="submit" variant="primary" busy={create.isPending}>
            {form.sendInvite ? t("team.sendInvitation") : t("team.addPerson")}
          </Button>
        </DialogActions>
      </form>
      </Dialog>
      <InviteLinkDialog link={link} onClose={() => setLink(null)} />
    </>
  );
}

/* ---- Teams ------------------------------------------------------------------------------- */

function TeamsSection({ teams, loading, people }: { teams: Team[]; loading: boolean; people: Person[] }) {
  const { t } = useTranslation();
  const perms = usePermissions();
  const [editing, setEditing] = useState<{ open: boolean; team: Team | null }>({ open: false, team: null });
  const names = new Map(people.map((p) => [p.id, p.name]));
  const open = (team: Team | null) => setEditing({ open: true, team });

  return (
    <section className="section">
      <div className="row" style={{ marginBottom: 4 }}>
        <h2 className="spacer">{t("team.teamsTitle")}</h2>
        {perms.isAdmin && (
          <Button size="sm" onClick={() => open(null)}>
            {t("team.newTeam")}
          </Button>
        )}
      </div>
      <p className="muted" style={{ marginBottom: 12 }}>
        {t("team.teamsLead")}
      </p>
      <table className="ledger">
        <thead>
          <tr>
            <th>{t("team.teamName")}</th>
            <th>{t("team.people")}</th>
            <th className="num">{t("team.members")}</th>
            {perms.isAdmin && <th />}
          </tr>
        </thead>
        <tbody>
          {loading && <LoadingRow colSpan={perms.isAdmin ? 4 : 3} />}
          {!loading && teams.length === 0 && (
            <tr>
              <td colSpan={perms.isAdmin ? 4 : 3} className="muted">
                {t("team.noTeams")}
              </td>
            </tr>
          )}
          {teams.map((tm) => {
            const memberNames = tm.membership_ids
              .map((id) => names.get(id))
              .filter((n): n is string => !!n)
              .sort((a, b) => a.localeCompare(b));
            const shown = memberNames.slice(0, 4).join(", ");
            return (
              <tr key={tm.id}>
                <td>{tm.name}</td>
                <td className="muted">
                  {shown}
                  {memberNames.length > 4 && ` ${t("team.andMore", { count: memberNames.length - 4 })}`}
                </td>
                <td className="num">{tm.membership_ids.length}</td>
                {perms.isAdmin && (
                  <td className="num" style={{ width: 1 }}>
                    <Button size="sm" variant="ghost" onClick={() => open(tm)} aria-label={t("team.editTeamNamed", { name: tm.name })}>
                      {t("app.edit")}
                    </Button>
                  </td>
                )}
              </tr>
            );
          })}
        </tbody>
      </table>
      <TeamDialog
        open={editing.open}
        team={editing.team}
        people={people}
        onOpenChange={(o) => setEditing((e) => ({ ...e, open: o }))}
      />
    </section>
  );
}

function TeamDialog({
  open,
  team,
  people,
  onOpenChange,
}: {
  open: boolean;
  team: Team | null;
  people: Person[];
  onOpenChange: (open: boolean) => void;
}) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const toast = useToast();
  const [name, setName] = useState("");
  const [members, setMembers] = useState<string[]>([]);
  const [confirmDelete, setConfirmDelete] = useState(false);

  const done = () => {
    void qc.invalidateQueries({ queryKey: ["teams"] });
    void qc.invalidateQueries({ queryKey: ["people"] });
  };
  const save = useMutation({
    mutationFn: () =>
      team
        ? unwrap(api.PATCH("/api/v1/teams/{id}", { params: { path: { id: team.id } }, body: { name, membership_ids: members } }))
        : unwrap(api.POST("/api/v1/teams", { body: { name, membership_ids: members } })),
    onSuccess: () => {
      done();
      toast(team ? t("app.saved") : t("team.teamCreated"));
      onOpenChange(false);
    },
  });
  const remove = useMutation({
    mutationFn: () => unwrap(api.DELETE("/api/v1/teams/{id}", { params: { path: { id: team!.id } } })),
    onSuccess: () => {
      done();
      setConfirmDelete(false);
      toast(t("team.teamDeleted"));
      onOpenChange(false);
    },
    // Back to the team dialog, which shows the error.
    onError: () => setConfirmDelete(false),
  });

  useEffect(() => {
    if (!open) return;
    setName(team?.name ?? "");
    setMembers(team?.membership_ids ?? []);
    save.reset();
    remove.reset();
  }, [open, team]);

  // Deactivated members stay in the team but aren't offered in the picker.
  const hidden = members.filter((id) => !people.some((p) => p.id === id));
  const toggle = (id: string, on: boolean) => setMembers((m) => (on ? [...m, id] : m.filter((x) => x !== id)));
  const err = save.error ? errorInfo(save.error) : null;
  const removeErr = remove.error ? errorInfo(remove.error) : null;

  return (
    <>
      <Dialog open={open && !confirmDelete} onOpenChange={onOpenChange} title={team ? t("team.editTeam") : t("team.newTeam")}>
        <form
          className="stack"
          onSubmit={(e) => {
            e.preventDefault();
            save.mutate();
          }}
        >
          {(err && !Object.keys(err.fields).length) || removeErr ? (
            <p className="notice notice-error" role="alert">
              {(removeErr ?? err)!.message}
            </p>
          ) : null}
          <TextField label={t("team.teamName")} hint={t("team.teamNameHint")} value={name} onChange={setName} error={err?.fields.name} autoFocus required />
          <fieldset style={{ border: 0, margin: 0, padding: 0 }}>
            <legend className="checklist-legend">{t("team.members")}</legend>
            {people.length === 0 ? (
              <p className="muted">{t("team.noOneToPick")}</p>
            ) : (
              <div className="checklist">
                {people.map((p) => (
                  <Checkbox key={p.id} checked={members.includes(p.id)} onChange={(on) => toggle(p.id, on)} label={p.name} />
                ))}
              </div>
            )}
            {hidden.length > 0 && (
              <p className="field-hint" style={{ marginTop: 4 }}>
                {t("team.hiddenMembers", { count: hidden.length })}
              </p>
            )}
            {err?.fields.membership_ids && <p className="field-error">{err.fields.membership_ids}</p>}
          </fieldset>
          <DialogActions>
            {team && (
              <Button variant="danger" onClick={() => setConfirmDelete(true)} style={{ marginRight: "auto" }}>
                {t("team.deleteTeam")}
              </Button>
            )}
            <Button onClick={() => onOpenChange(false)}>{t("app.cancel")}</Button>
            <Button type="submit" variant="primary" busy={save.isPending}>
              {team ? t("app.save") : t("team.createTeam")}
            </Button>
          </DialogActions>
        </form>
      </Dialog>
      <ConfirmDialog
        open={open && confirmDelete}
        onOpenChange={(o) => !o && setConfirmDelete(false)}
        title={t("team.deleteTeamTitle")}
        body={t("team.deleteTeamConfirm", { name: team?.name ?? "" })}
        confirmLabel={t("team.deleteTeam")}
        busy={remove.isPending}
        onConfirm={() => remove.mutate()}
      />
    </>
  );
}
