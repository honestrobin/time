// SPDX-License-Identifier: AGPL-3.0-only
import { queryOptions } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { SelectField } from "../../design";
import { api, unwrap, type Schemas } from "../../lib/api";

export type Person = Schemas["PersonView"];
export type Team = Schemas["TeamView"];
export type Role = "admin" | "manager" | "member";

export const ROLES: Role[] = ["member", "manager", "admin"];

export const asRole = (r: string): Role => (ROLES.includes(r as Role) ? (r as Role) : "member");

/** Everyone the caller can see, active or not (the list endpoint is capped at 500 per page). */
export const peopleQuery = queryOptions({
  queryKey: ["people", "all"],
  queryFn: async () => (await unwrap(api.GET("/api/v1/people", { params: { query: { limit: 500 } } }))).data,
});

export const personQuery = (id: string) =>
  queryOptions({
    queryKey: ["people", "one", id],
    queryFn: () => unwrap(api.GET("/api/v1/people/{id}", { params: { path: { id } } })),
  });

export const teamsQuery = queryOptions({ queryKey: ["teams"], queryFn: () => unwrap(api.GET("/api/v1/teams")) });

export const byName = <T extends { name: string }>(a: T, b: T) => a.name.localeCompare(b.name, undefined, { sensitivity: "base" });

const hoursFormat = new Intl.NumberFormat(navigator.language || "en", { maximumFractionDigits: 2 });

/** Weekly capacity in plain hours, e.g. "40 h" or "32.5 h". */
export function useHours() {
  const { t } = useTranslation();
  return (seconds: number) => t("team.hours", { hours: hoursFormat.format(seconds / 3600) });
}

/** "Invited" or "Not invited yet"; nothing for people who can sign in. */
export function StatusBadge({ person, showInactive = false }: { person: Person; showInactive?: boolean }) {
  const { t } = useTranslation();
  if (!person.is_active) return showInactive ? <span className="badge badge-signal">{t("team.statusDeactivated")}</span> : null;
  if (person.status === "invited") return <span className="badge badge-warn">{t("team.statusInvited")}</span>;
  if (person.status === "pending_invite") return <span className="badge">{t("team.statusPending")}</span>;
  return null;
}

export function RoleField({
  value,
  onChange,
  error,
  disabled,
  className,
}: {
  value: Role;
  onChange: (r: Role) => void;
  error?: string;
  disabled?: boolean;
  className?: string;
}) {
  const { t } = useTranslation();
  return (
    <SelectField
      label={t("team.role")}
      value={value}
      onChange={onChange}
      options={ROLES.map((r) => ({ value: r, label: t(`team.roles.${r}`) }))}
      hint={t(`team.roleHints.${value}`)}
      error={error}
      disabled={disabled}
      className={className}
    />
  );
}
