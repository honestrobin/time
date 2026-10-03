// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";
import { Button } from "../../design";
import { api, unwrap } from "../../lib/api";
import { formatClock } from "../../lib/format";
import { liveSeconds, useInvalidateTime, useNow, useRunningTimer } from "./hooks";

/**
 * The running timer, shown on every page: a thin red line across the top of the app and
 * a strip with the live clock. Red is reserved for this (and totals and over-budget).
 */
export function TimerStrip() {
  const { t } = useTranslation();
  const { entry, fetchedAt } = useRunningTimer();
  const now = useNow(!!entry);
  const invalidate = useInvalidateTime();
  const stop = useMutation({
    mutationFn: (id: string) => unwrap(api.POST("/api/v1/time_entries/{id}/stop", { params: { path: { id } } })),
    onSuccess: () => invalidate(),
  });
  if (!entry) return null;
  const seconds = liveSeconds(entry, now, fetchedAt);
  return (
    <div className="timer-strip" role="status" aria-live="off">
      <span className="timer-line" aria-hidden />
      <span className="timer-dot" aria-hidden />
      <Link to="/time/day/$date" params={{ date: entry.spent_date }} className="timer-what">
        <strong>{entry.project.name}</strong>
        <span className="muted">
          {entry.task.name}
          {entry.notes ? `: ${entry.notes}` : ""}
        </span>
      </Link>
      <span className="timer-clock" aria-label={t("time.elapsed")}>
        {formatClock(seconds)}
      </span>
      <Button size="sm" variant="secondary" onClick={() => stop.mutate(entry.id)} busy={stop.isPending}>
        {t("time.stop")}
      </Button>
    </div>
  );
}
