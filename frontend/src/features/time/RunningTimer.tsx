// SPDX-License-Identifier: AGPL-3.0-only
import { Link, useLocation } from "@tanstack/react-router";
import { useEffect } from "react";
import { useTranslation } from "react-i18next";
import { formatDuration } from "../../lib/format";
import { liveSeconds, useAccountSettings, useNow, useRunningTimer } from "./hooks";
import { runningTabTitle } from "./track";

/** True on today's Track, where the timer itself is on screen and the pill would repeat it. */
function useTimerInView() {
  const pathname = useLocation({ select: (l) => l.pathname });
  const { today } = useAccountSettings();
  return pathname === "/" || pathname === `/day/${today}`;
}

/**
 * The running timer on every other page: a quiet pill in the top bar with the time and the
 * project, one click from the timer itself.
 */
export function TimerPill() {
  const { t } = useTranslation();
  const { entry, fetchedAt } = useRunningTimer();
  const now = useNow(!!entry);
  const inView = useTimerInView();
  if (!entry || inView) return null;
  const time = formatDuration(liveSeconds(entry, now, fetchedAt));
  return (
    <Link
      to="/day/$date"
      params={{ date: entry.spent_date }}
      className="timer-pill"
      aria-label={t("time.runningPill", { time, project: entry.project.name })}
    >
      <span className="live-dot" aria-hidden />
      <span>{time}</span>
      <span>{entry.project.name}</span>
    </Link>
  );
}

/**
 * The browser tab while a timer runs, on every page: the running time and the project in the
 * title, and the robin with an orange dot as its icon. Both go back when the timer stops.
 */
export function RunningTimerTab() {
  const { entry, fetchedAt } = useRunningTimer();
  const now = useNow(!!entry);
  const title = entry ? runningTabTitle(liveSeconds(entry, now, fetchedAt), entry.project.name) : null;
  const running = title !== null;

  useEffect(() => {
    if (!running) return;
    const before = document.title;
    const icon = document.querySelector<HTMLLinkElement>("link[rel='icon']");
    const iconBefore = icon?.getAttribute("href");
    icon?.setAttribute("href", "/favicon-running.svg");
    return () => {
      document.title = before;
      if (icon && iconBefore) icon.setAttribute("href", iconBefore);
    };
  }, [running]);

  useEffect(() => {
    if (title) document.title = title;
  }, [title]);

  return null;
}
