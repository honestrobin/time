// SPDX-License-Identifier: AGPL-3.0-only
import { useQuery } from "@tanstack/react-query";
import { Link, useLocation, useNavigate, useParams } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";
import { Button, Select } from "../../design";
import { api, unwrap } from "../../lib/api";
import { addDays, startOfWeek } from "../../lib/dates";
import { formatDate } from "../../lib/format";
import { useHotkeys } from "../../lib/hotkeys";
import { useMe, usePermissions } from "../../lib/session";
import { DayView } from "./DayView";
import { useAccountSettings } from "./hooks";
import { WeekView } from "./WeekView";
import "./time.css";

/** Time: a day view (entries and timers) and a week view (grid), like a paper time card. */
export function TimePage() {
  const { t } = useTranslation();
  const account = useAccountSettings();
  const perms = usePermissions();
  const me = useMe();
  const navigate = useNavigate();
  const location = useLocation();
  const params = useParams({ strict: false }) as { date?: string };
  const search = location.search as { person?: string };
  const mode: "day" | "week" = location.pathname.startsWith("/week/") ? "week" : "day";
  const date = params.date ?? account.today;
  const weekStart = startOfWeek(date, account.weekStart);
  const personId = search.person;

  const go = (m: "day" | "week", d: string, person = personId) =>
    void navigate({ to: m === "day" ? "/day/$date" : "/week/$date", params: { date: d }, search: person ? { person } : {} });
  const step = mode === "day" ? 1 : 7;
  const prev = () => go(mode, addDays(mode === "day" ? date : weekStart, -step));
  const next = () => go(mode, addDays(mode === "day" ? date : weekStart, step));

  useHotkeys({
    ArrowLeft: prev,
    ArrowRight: next,
    d: () => go("day", mode === "week" && date === weekStart && account.today >= weekStart && account.today <= addDays(weekStart, 6) ? account.today : date),
    w: () => go("week", weekStart),
  });

  const people = useQuery({
    queryKey: ["people", "timesheet-picker"],
    queryFn: () => unwrap(api.GET("/api/v1/people", { params: { query: { is_active: true, limit: 500 } } })),
    enabled: perms.isManagerOrAdmin,
  });

  const label =
    mode === "day" ? formatDate(date, "long") : `${formatDate(weekStart, "medium")} – ${formatDate(addDays(weekStart, 6), "medium")}`;

  return (
    <div className="page">
      <header className="page-header">
        <h1>{t("time.title")}</h1>
        <div className="actions time-nav">
          {perms.isManagerOrAdmin && mode === "week" && (people.data?.data.length ?? 0) > 1 && (
            <div style={{ width: 200 }}>
              <Select
                aria-label={t("time.person")}
                value={personId ?? me.current_membership_id ?? undefined}
                onChange={(v) => go("week", weekStart, v === me.current_membership_id ? undefined : v)}
                options={(people.data?.data ?? []).map((p) => ({ value: p.id, label: p.id === me.current_membership_id ? t("time.me") : p.name }))}
              />
            </div>
          )}
          <div className="segmented" role="navigation" aria-label={t("time.title")}>
            <Link to="/day/$date" params={{ date: mode === "week" ? (account.today >= weekStart && account.today <= addDays(weekStart, 6) ? account.today : weekStart) : date }} aria-current={mode === "day" ? "page" : undefined}>
              {t("time.day")}
            </Link>
            <Link to="/week/$date" params={{ date: weekStart }} aria-current={mode === "week" ? "page" : undefined}>
              {t("time.week")}
            </Link>
          </div>
          <Button size="sm" variant="ghost" className="nav-arrow" aria-label={mode === "day" ? t("time.previousDay") : t("time.previousWeek")} onClick={prev}>
            ‹
          </Button>
          <span className="time-date">{label}</span>
          <Button size="sm" variant="ghost" className="nav-arrow" aria-label={mode === "day" ? t("time.nextDay") : t("time.nextWeek")} onClick={next}>
            ›
          </Button>
          <Button size="sm" onClick={() => go(mode, mode === "day" ? account.today : startOfWeek(account.today, account.weekStart))}>
            {t("time.today")}
          </Button>
        </div>
      </header>
      {account.loaded && (mode === "day" ? <DayView date={date} /> : <WeekView date={weekStart} personId={personId} />)}
    </div>
  );
}
