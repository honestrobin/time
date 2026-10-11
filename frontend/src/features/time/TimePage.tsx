// SPDX-License-Identifier: AGPL-3.0-only
import { useQuery } from "@tanstack/react-query";
import { Link, useLocation, useNavigate, useParams } from "@tanstack/react-router";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { PageLoading, Select } from "../../design";
import { api, unwrap } from "../../lib/api";
import { addDays, startOfWeek } from "../../lib/dates";
import { formatDayHeading, formatWeekHeading } from "../../lib/format";
import { useHotkeys } from "../../lib/hotkeys";
import { useMe, usePermissions } from "../../lib/session";
import { DayView } from "./DayView";
import { useAccountSettings } from "./hooks";
import { WeekView, type WeekShape } from "./WeekView";
import "./time.css";

const SHAPE_KEY = "honestrobin.weekShape";

/** Table or Strip, remembered in this browser only: a convenience, so it may be forgotten. */
function useWeekShape() {
  const [shape, setShape] = useState<WeekShape>(() => {
    try {
      return localStorage.getItem(SHAPE_KEY) === "strip" ? "strip" : "table";
    } catch {
      return "table";
    }
  });
  const choose = (next: WeekShape) => {
    setShape(next);
    try {
      localStorage.setItem(SHAPE_KEY, next);
    } catch {
      // Storage is off in this browser: the choice lasts until the page is left.
    }
  };
  return [shape, choose] as const;
}

/**
 * Track: a day (today's timer and the day's entries) and a week (a table, or strips of paper),
 * like a paper time card. Both show one chosen day, the address's date, so Day and Week keep it.
 */
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
  const [shape, setShape] = useWeekShape();

  const go = (m: "day" | "week", d: string, person = personId, replace = false) =>
    void navigate({ to: m === "day" ? "/day/$date" : "/week/$date", params: { date: d }, search: person ? { person } : {}, replace });
  const step = mode === "day" ? 1 : 7;
  const prev = () => go(mode, addDays(date, -step));
  const next = () => go(mode, addDays(date, step));

  useHotkeys({
    ArrowLeft: prev,
    ArrowRight: next,
    d: () => go("day", date),
    w: () => go("week", date),
  });

  const people = useQuery({
    queryKey: ["people", "timesheet-picker"],
    queryFn: () => unwrap(api.GET("/api/v1/people", { params: { query: { is_active: true, limit: 500 } } })),
    enabled: perms.isManagerOrAdmin,
  });

  const isTodayInView = mode === "day" ? date === account.today : account.today >= weekStart && account.today <= addDays(weekStart, 6);
  const label = mode === "day" ? formatDayHeading(date) : formatWeekHeading(weekStart, addDays(weekStart, 6), account.today.slice(0, 4));

  return (
    <div className={mode === "week" ? "track is-week" : "track"}>
      <header className="track-head">
        <button type="button" className="step-button" aria-label={mode === "day" ? t("time.previousDay") : t("time.previousWeek")} onClick={prev}>
          ‹
        </button>
        <h1>{label}</h1>
        {isTodayInView ? (
          <span className="today-badge">{mode === "day" ? t("time.today") : t("time.thisWeekBadge")}</span>
        ) : (
          <button type="button" className="today-link" onClick={() => go(mode, account.today)}>
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
                onChange={(v) => go("week", date, v === me.current_membership_id ? undefined : v)}
                options={(people.data?.data ?? []).map((p) => ({ value: p.id, label: p.id === me.current_membership_id ? t("time.me") : p.name }))}
              />
            </div>
          )}
          {mode === "week" && (
            <div className="shape-switch" role="group" aria-label={t("time.showAs")}>
              <button type="button" aria-pressed={shape === "table"} onClick={() => setShape("table")}>
                {t("time.table")}
              </button>
              <span aria-hidden="true">/</span>
              <button type="button" aria-pressed={shape === "strip"} onClick={() => setShape("strip")}>
                {t("time.strip")}
              </button>
            </div>
          )}
          <div className="segmented" role="group" aria-label={t("time.show")}>
            <Link to="/day/$date" params={{ date }} aria-current={mode === "day" ? "page" : undefined}>
              {t("time.day")}
            </Link>
            <Link to="/week/$date" params={{ date }} aria-current={mode === "week" ? "page" : undefined}>
              {t("time.week")}
            </Link>
          </div>
        </div>
      </header>
      {!account.loaded ? (
        <PageLoading />
      ) : mode === "day" ? (
        <DayView date={date} />
      ) : (
        <WeekView
          key={`${weekStart}:${personId ?? ""}`}
          date={weekStart}
          selected={date}
          onSelect={(d) => go("week", d, personId, true)}
          shape={shape}
          personId={personId}
        />
      )}
    </div>
  );
}
