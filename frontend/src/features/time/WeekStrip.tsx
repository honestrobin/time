// SPDX-License-Identifier: AGPL-3.0-only
import { useEffect, useRef, useState, type CSSProperties } from "react";
import { useTranslation } from "react-i18next";
import { formatDayHeading, formatDuration, formatTimeOfDay, formatWeekday } from "../../lib/format";
import { useAccountSettings, type TimeEntry, type Week } from "./hooks";
import { layoutStrips } from "./strip";

/** Five inks, one per client in the order they appear in the week; a sixth client starts again. */
const INKS = 5;

/** How wide an element is, kept up to date: the ruler labels as many hours as fit. */
function useWidth<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  const [width, setWidth] = useState(0);
  useEffect(() => {
    const el = ref.current;
    if (!el || typeof ResizeObserver === "undefined") return;
    const watch = new ResizeObserver(([e]) => setWidth(e.contentRect.width));
    watch.observe(el);
    return () => watch.disconnect();
  }, []);
  return [ref, width] as const;
}

/**
 * The week as strips of chart paper, one a day: each entry a band where it ran, the chosen day
 * taller with its projects written in. A view of the table's numbers, never a second place to edit.
 */
export function WeekStrip({
  week,
  selected,
  onSelect,
  seconds,
}: {
  week: Week;
  selected: string;
  onSelect: (day: string) => void;
  seconds: (e: TimeEntry) => number;
}) {
  const { t } = useTranslation();
  const style = useAccountSettings().durationStyle;
  const strips = layoutStrips(week.days, week.entries, seconds);
  const span = strips.to - strips.from;
  const at = (minutes: number) => (minutes - strips.from) / span;
  const hours = Array.from({ length: span / 60 + 1 }, (_, i) => strips.from / 60 + i);
  const clock = (hour: number) => formatTimeOfDay(`${hour}:00`);
  const [lane, laneWidth] = useWidth<HTMLDivElement>();
  // Label every hour if they fit, else every 2nd, 3rd, 4th or 6th: about 7px a character, plus room.
  const room = clock(13).length * 7 + 10;
  const every = [1, 2, 3, 4, 6].find((n) => (laneWidth / (span / 60)) * n >= room) ?? 6;
  const notBillable = week.entries.some((e) => !e.billable);
  const total = strips.days.reduce((s, d) => s + d.seconds, 0);

  return (
    <section className="week-strip" aria-label={t("time.stripLabel", { from: clock(strips.from / 60), to: clock(strips.to / 60) })}>
      <div className="strip-ruler" aria-hidden="true">
        <div className="strip-lane" ref={lane}>
          {laneWidth > 0 &&
            hours
              .slice(0, -1)
              .filter((h) => (h - hours[0]) % every === 0)
              .map((h) => (
                <span key={h} style={{ "--at": at(h * 60) } as CSSProperties}>
                  {clock(h)}
                </span>
              ))}
        </div>
      </div>
      {strips.days.map((day) => {
        const big = day.date === selected;
        return (
          <button
            type="button"
            key={day.date}
            className={big ? "strip is-selected" : "strip"}
            aria-pressed={big}
            aria-label={
              day.seconds
                ? t("time.stripDay", { day: formatDayHeading(day.date), time: formatDuration(day.seconds, style) })
                : t("time.stripDayEmpty", { day: formatDayHeading(day.date) })
            }
            onClick={() => onSelect(day.date)}
          >
            {hours.map((h) => (
              <span key={h} className="strip-hour" aria-hidden="true" style={{ "--at": at(h * 60) } as CSSProperties} />
            ))}
            <span className="strip-day" aria-hidden="true">
              {formatWeekday(day.date)} {Number(day.date.slice(8))}
            </span>
            {day.bands.map((b) => (
              <span
                key={b.entry.id}
                aria-hidden="true"
                className={[
                  "band",
                  b.entry.is_running && "is-running",
                  b.untimed && "is-untimed",
                  !b.entry.billable && "is-not-billable",
                ]
                  .filter(Boolean)
                  .join(" ")}
                style={{ "--at": at(b.start), "--long": (b.end - b.start) / span, "--band-ink": `var(--client-${b.client % INKS})` } as CSSProperties}
              >
                {big && (
                  <>
                    <span className="band-project">{b.entry.project.name}</span>
                    <span className="band-time">{formatDuration(seconds(b.entry), style)}</span>
                  </>
                )}
              </span>
            ))}
            <span className={day.seconds ? "strip-total" : "strip-total is-zero"} aria-hidden="true">
              {day.seconds ? formatDuration(day.seconds, style) : "–"}
            </span>
          </button>
        );
      })}
      <div className="strip-foot">
        <span className="strip-foot-label">{t("time.thisWeek")}</span>
        <ul className="strip-legend" aria-label={t("time.stripLegend")}>
          {strips.clients.map((c, i) => (
            <li key={c.id}>
              <span className="swatch" style={{ "--band-ink": `var(--client-${i % INKS})` } as CSSProperties} />
              {c.name}
            </li>
          ))}
          {notBillable && (
            <li>
              <span className="swatch is-not-billable" />
              {t("time.nonBillable")}
            </li>
          )}
        </ul>
        <span className="strip-foot-total">{formatDuration(total, style)}</span>
      </div>
    </section>
  );
}
