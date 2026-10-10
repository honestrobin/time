// SPDX-License-Identifier: AGPL-3.0-only
import { useQuery } from "@tanstack/react-query";
import { Link, useLocation, useNavigate, useParams } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";
import { PageLoading, Select } from "../../design";
import { api, unwrap } from "../../lib/api";
import { addDays, startOfWeek } from "../../lib/dates";
import { formatDate, formatDayHeading } from "../../lib/format";
import { useHotkeys } from "../../lib/hotkeys";
import { useMe, usePermissions } from "../../lib/session";
import { DayView } from "./DayView";
import { useAccountSettings } from "./hooks";
import { WeekView } from "./WeekView";
import "./time.css";

/** Track: a day (today's timer and the day's entries) and a week (a grid), like a paper time card. */
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

  const isTodayInView = mode === "day" ? date === account.today : account.today >= weekStart && account.today <= addDays(weekStart, 6);
  const label =
    mode === "day" ? formatDayHeading(date) : `${formatDate(weekStart, "medium")} – ${formatDate(addDays(weekStart, 6), "medium")}`;

  return (
    <div className="track">
      <header className="track-head">
        <button type="button" className="step-button" aria-label={mode === "day" ? t("time.previousDay") : t("time.previousWeek")} onClick={prev}>
          ‹
        </button>
        <h1>{label}</h1>
        {isTodayInView ? (
          <span className="today-badge">{mode === "day" ? t("time.today") : t("time.thisWeekBadge")}</span>
        ) : (
          <button type="button" className="today-link" onClick={() => go(mode, mode === "day" ? account.today : startOfWeek(account.today, account.weekStart))}>
            {mode === "day" ? t("time.goToToday") : t("time.goToThisWeek")}
          </button>
        )}
        <button type="button" className="step-button" aria-label={mode === "day" ? t("time.nextDay") : t("time.nextWeek")} onClick={next}>
          ›
        </button>
        <div className="track-head-end">
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
          <div className="segmented" role="group" aria-label={t("time.show")}>
            <Link to="/day/$date" params={{ date: mode === "week" ? (account.today >= weekStart && account.today <= addDays(weekStart, 6) ? account.today : weekStart) : date }} aria-current={mode === "day" ? "page" : undefined}>
              {t("time.day")}
            </Link>
            <Link to="/week/$date" params={{ date: weekStart }} aria-current={mode === "week" ? "page" : undefined}>
              {t("time.week")}
            </Link>
          </div>
        </div>
      </header>
      {!account.loaded ? <PageLoading /> : mode === "day" ? <DayView date={date} /> : <WeekView date={weekStart} personId={personId} />}
    </div>
  );
}
