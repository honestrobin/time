// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation, useQuery } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { Button, Checkbox, Select, SelectField, useToast } from "../../design";
import { api, errorInfo, unwrap, type Schemas } from "../../lib/api";
import { formatDuration, formatMoney } from "../../lib/format";
import { useMe, usePermissions } from "../../lib/session";
import { CellDuration, CellMoney } from "./cells";
import { activePeopleQuery, byName, useAccountDefaults, useProjectCache, type Project, type ProjectMember } from "./queries";

type MemberPatch = { [K in keyof Schemas["ProjectMemberInput"]]?: Schemas["ProjectMemberInput"][K] | null };

/** People on the project: who manages it, their rates and budgets. */
export function ProjectTeamTab({ project, onRatesChanged }: { project: Project; onRatesChanged: () => void }) {
  const { t } = useTranslation();
  const toast = useToast();
  const perms = usePermissions();
  const me = useMe();
  const cache = useProjectCache();
  const { durationStyle } = useAccountDefaults();
  const peopleQ = useQuery(activePeopleQuery);
  const people = peopleQ.data?.data ?? [];
  const personById = new Map(people.map((p) => [p.id, p]));
  const currency = project.client.currency;
  const [adding, setAdding] = useState<string | undefined>();

  const showRate = project.is_billable && project.bill_by === "people" && perms.canSeeRates;
  const showBudget = project.budget_by === "person";

  const update = useMutation({
    mutationFn: ({ pm, body }: { pm: ProjectMember; body: MemberPatch; rates?: boolean; message?: string }) =>
      unwrap(
        api.PATCH("/api/v1/projects/{id}/members/{projectMemberId}", {
          params: { path: { id: project.id, projectMemberId: pm.id } },
          body: body as Schemas["ProjectMemberInput"],
        }),
      ),
    onSuccess: (p, vars) => {
      cache(p);
      toast(vars.message ?? t("app.saved"));
      if (vars.rates) onRatesChanged();
    },
    onError: (e) => toast(errorInfo(e).message, "error"),
  });

  const add = useMutation({
    mutationFn: (membershipId: string) =>
      unwrap(api.POST("/api/v1/projects/{id}/members", { params: { path: { id: project.id } }, body: { membership_id: membershipId } })),
    onSuccess: (p, membershipId) => {
      cache(p);
      setAdding(undefined);
      toast(t("projects.team.added", { name: personById.get(membershipId)?.name ?? "" }));
    },
  });

  const remove = useMutation({
    mutationFn: (pm: ProjectMember) =>
      unwrap(api.DELETE("/api/v1/projects/{id}/members/{projectMemberId}", { params: { path: { id: project.id, projectMemberId: pm.id } } })),
    onSuccess: (p, pm) => {
      cache(p);
      // People with tracked time stay on the project, deactivated, so their history keeps its context.
      const kept = p.members.some((m) => m.id === pm.id);
      toast(kept ? t("projects.team.deactivatedInstead", { name: pm.name }) : t("projects.team.removed", { name: pm.name }));
    },
    onError: (e) => toast(errorInfo(e).message, "error"),
  });

  const onProject = new Set(project.members.map((m) => m.membership_id));
  const addable = people.filter((p) => p.is_active && !onProject.has(p.id)).sort(byName);
  const addErr = add.error ? errorInfo(add.error) : null;
  const submitAdd = (e: FormEvent) => {
    e.preventDefault();
    if (adding) add.mutate(adding);
  };

  const budgetTotal = project.members.reduce((sum, m) => sum + (m.budget_seconds ?? 0), 0);
  const cols = 3 + (showRate ? 1 : 0) + (showBudget ? 1 : 0);

  return (
    <section>
      <p className="muted inline-note" style={{ marginBottom: 16 }}>
        {showRate ? t("projects.team.leadRates") : t("projects.team.lead")}
      </p>
      <table className="ledger">
        <thead>
          <tr>
            <th>{t("projects.team.person")}</th>
            <th className="col-narrow" style={{ textAlign: "center" }}>
              {t("projects.team.manager")}
            </th>
            {showRate && <th>{t("projects.team.rate")}</th>}
            {showBudget && <th className="num">{t("projects.team.budgetHours")}</th>}
            <th>
              <span className="sr-only">{t("projects.actions")}</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {project.members.length === 0 && (
            <tr>
              <td colSpan={cols} className="muted">
                {t("projects.team.empty")}
              </td>
            </tr>
          )}
          {project.members.map((pm) => {
            const person = personById.get(pm.membership_id);
            const isMe = pm.membership_id === me.current_membership_id;
            const canManage = person ? person.role !== "member" : true;
            const defaultRate = person?.default_billable_rate;
            return (
              <tr key={pm.id} className={pm.is_active ? undefined : "is-archived"}>
                <td>
                  {pm.name}
                  {isMe && <span className="muted"> ({t("projects.team.you")})</span>}
                  {!pm.is_active && <span className="badge" style={{ marginLeft: 8 }}>{t("projects.team.inactive")}</span>}
                </td>
                <td title={canManage ? undefined : t("projects.team.managerRoleHint")}>
                  <Checkbox
                    checked={pm.is_manager}
                    disabled={(!canManage && !pm.is_manager) || (isMe && !perms.isAdmin && pm.is_manager)}
                    onChange={(v) => update.mutate({ pm, body: { is_manager: v } })}
                    label={<span className="sr-only">{t("projects.team.managerFor", { name: pm.name })}</span>}
                  />
                </td>
                {showRate && (
                  <td>
                    <div className="row">
                      <div className="cell-select">
                        <Select
                          aria-label={t("projects.team.rateModeFor", { name: pm.name })}
                          value={pm.use_default_rates ? "default" : "custom"}
                          onChange={(v) => update.mutate({ pm, body: { use_default_rates: v === "default" }, rates: true })}
                          options={[
                            {
                              value: "default",
                              label:
                                defaultRate != null
                                  ? t("projects.team.defaultRateAmount", { rate: formatMoney(defaultRate, currency) })
                                  : t("projects.team.defaultRate"),
                            },
                            { value: "custom", label: t("projects.team.customRate") },
                          ]}
                        />
                      </div>
                      {!pm.use_default_rates && (
                        <CellMoney
                          label={t("projects.team.rateFor", { name: pm.name })}
                          value={pm.hourly_rate ?? null}
                          currency={currency}
                          onCommit={(v) => update.mutate({ pm, body: { hourly_rate: v }, rates: true })}
                        />
                      )}
                    </div>
                  </td>
                )}
                {showBudget && (
                  <td className="num">
                    <CellDuration
                      label={t("projects.team.budgetFor", { name: pm.name })}
                      value={pm.budget_seconds ?? null}
                      style={durationStyle}
                      onCommit={(v) => update.mutate({ pm, body: { budget_seconds: v } })}
                    />
                  </td>
                )}
                <td className="actions">
                  {pm.is_active ? (
                    <Button size="sm" variant="ghost" onClick={() => remove.mutate(pm)} busy={remove.isPending && remove.variables?.id === pm.id} disabled={isMe && !perms.isAdmin}>
                      {t("projects.team.remove")}
                    </Button>
                  ) : (
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => update.mutate({ pm, body: { is_active: true }, message: t("projects.team.reactivated", { name: pm.name }) })}
                    >
                      {t("projects.team.reactivate")}
                    </Button>
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
        {showBudget && project.members.length > 0 && (
          <tfoot>
            <tr>
              <td className="total">{t("projects.overview.total")}</td>
              <td className="total" />
              {showRate && <td className="total" />}
              <td className="total num">{formatDuration(budgetTotal, durationStyle)}</td>
              <td className="total" />
            </tr>
          </tfoot>
        )}
      </table>

      {showRate && <p className="field-hint" style={{ marginTop: 8 }}>{t("projects.team.rateHint")}</p>}

      <form className="add-row" onSubmit={submitAdd}>
        {addable.length > 0 ? (
          <>
            <SelectField
              label={t("projects.team.addLabel")}
              value={adding}
              onChange={setAdding}
              options={addable.map((p) => ({ value: p.id, label: p.name }))}
              placeholder={t("projects.team.choose")}
              error={addErr?.fields.membership_id ?? (addErr && !Object.keys(addErr.fields).length ? addErr.message : undefined)}
            />
            <Button type="submit" disabled={!adding} busy={add.isPending}>
              {t("projects.team.add")}
            </Button>
          </>
        ) : (
          !peopleQ.isLoading && <p className="muted">{t("projects.team.allAdded")}</p>
        )}
      </form>
    </section>
  );
}
