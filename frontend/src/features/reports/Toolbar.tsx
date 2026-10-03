// SPDX-License-Identifier: AGPL-3.0-only
import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, Checkbox, Dialog, DialogActions, Menu, useToast } from "../../design";
import { downloadFile, errorInfo } from "../../lib/api";
import { formatDate, formatMonth } from "../../lib/format";
import type { Option } from "./options";
import { isCurrent, periodFor, shift, type Range, type Unit } from "./period";
import { FILTER_KEYS, type FilterKey, type ReportSearch } from "./search";

/** "September 2026", "Q3 2026", "28 Sep – 4 Oct 2026". */
export function rangeLabel(unit: Unit, range: Range, t: (k: string, o?: Record<string, unknown>) => string): string {
  if (!range.from || !range.to) return t("reports.period.allTime");
  if (unit === "month") return formatMonth(range.from.slice(0, 7));
  if (unit === "year") return range.from.slice(0, 4);
  if (unit === "quarter") return t("reports.period.quarterLabel", { quarter: Math.floor(Number(range.from.slice(5, 7)) / 3) + 1, year: range.from.slice(0, 4) });
  try {
    const f = new Intl.DateTimeFormat(undefined, { day: "numeric", month: "short", year: "numeric" });
    return f.formatRange(new Date(`${range.from}T12:00:00`), new Date(`${range.to}T12:00:00`));
  } catch {
    return `${formatDate(range.from, "short")} – ${formatDate(range.to, "short")}`;
  }
}

interface PeriodProps {
  unit: Unit;
  range: Range;
  today: string;
  weekStart: number;
  onChange: (unit: Unit, range: Range) => void;
}

/** ‹ September 2026 › with a menu to pick week, month, quarter, year, all time or custom dates. */
export function PeriodPicker({ unit, range, today, weekStart, onChange }: PeriodProps) {
  const { t } = useTranslation();
  const [custom, setCustom] = useState(false);
  const [from, setFrom] = useState(range.from ?? today);
  const [to, setTo] = useState(range.to ?? today);
  const pick = (u: Unit) => onChange(u, periodFor(u, today, weekStart));
  const current = isCurrent(unit, range, today, weekStart);
  return (
    <div className="period" role="group" aria-label={t("reports.period.label")}>
      {unit !== "all" && (
        <button type="button" className="btn btn-ghost btn-sm" aria-label={t("reports.period.previous")} onClick={() => onChange(unit, shift(unit, range, -1, weekStart))}>
          ‹
        </button>
      )}
      <Menu
        align="start"
        trigger={
          <button type="button" className="btn btn-secondary btn-sm period-label">
            {rangeLabel(unit, range, t)} <span aria-hidden>▾</span>
          </button>
        }
        items={[
          { label: t("reports.period.thisWeek"), onSelect: () => pick("week") },
          { label: t("reports.period.thisMonth"), onSelect: () => pick("month") },
          { label: t("reports.period.thisQuarter"), onSelect: () => pick("quarter") },
          { label: t("reports.period.thisYear"), onSelect: () => pick("year") },
          { label: t("reports.period.allTime"), onSelect: () => pick("all") },
          "separator",
          {
            label: t("reports.period.custom"),
            onSelect: () => {
              setFrom(range.from ?? today);
              setTo(range.to ?? today);
              setCustom(true);
            },
          },
        ]}
      />
      {unit !== "all" && (
        <button type="button" className="btn btn-ghost btn-sm" aria-label={t("reports.period.next")} onClick={() => onChange(unit, shift(unit, range, 1, weekStart))}>
          ›
        </button>
      )}
      {unit !== "all" && unit !== "custom" && !current && (
        <button type="button" className="btn btn-ghost btn-sm" onClick={() => pick(unit)}>
          {t(`reports.period.back.${unit}`)}
        </button>
      )}
      <Dialog open={custom} onOpenChange={setCustom} title={t("reports.period.customTitle")}>
        <form
          className="stack"
          onSubmit={(e) => {
            e.preventDefault();
            if (from && to) onChange("custom", from <= to ? { from, to } : { from: to, to: from });
            setCustom(false);
          }}
        >
          <div className="form-grid">
            <label className="field">
              <span className="field-label">{t("reports.period.from")}</span>
              <input className="input" type="date" value={from} onChange={(e) => setFrom(e.target.value)} required />
            </label>
            <label className="field">
              <span className="field-label">{t("reports.period.to")}</span>
              <input className="input" type="date" value={to} onChange={(e) => setTo(e.target.value)} required />
            </label>
          </div>
          <DialogActions>
            <Button type="button" onClick={() => setCustom(false)}>
              {t("app.cancel")}
            </Button>
            <Button type="submit" variant="primary">
              {t("reports.period.apply")}
            </Button>
          </DialogActions>
        </form>
      </Dialog>
    </div>
  );
}

interface FiltersProps {
  search: ReportSearch;
  available: FilterKey[];
  options: Record<FilterKey, Option[]>;
  showBillable: boolean;
  onChange: (patch: Partial<ReportSearch>) => void;
}

/** "Filter ▾" plus a chip per active filter. */
export function Filters({ search, available, options, showBillable, onChange }: FiltersProps) {
  const { t } = useTranslation();
  const [editing, setEditing] = useState<FilterKey | null>(null);
  const chips = FILTER_KEYS.filter((k) => search[k]?.length);
  const names = (k: FilterKey) => {
    const chosen = search[k] ?? [];
    const known = chosen.map((id) => options[k].find((o) => o.id === id)?.name).filter(Boolean) as string[];
    return chosen.length === 1 && known.length === 1 ? known[0] : t("reports.filters.selected", { count: chosen.length });
  };
  return (
    <div className="filters">
      {available.length > 0 && (
        <Menu
          align="start"
          trigger={
            <button type="button" className="btn btn-secondary btn-sm">
              {t("reports.filters.add")} <span aria-hidden>▾</span>
            </button>
          }
          items={available.map((k) => ({ label: t(`reports.filters.${k}`), onSelect: () => setEditing(k) }))}
        />
      )}
      {showBillable && (
        <div className="seg seg-sm" role="group" aria-label={t("reports.filters.billable")}>
          {([undefined, "yes", "no"] as const).map((b) => (
            <button key={b ?? "all"} type="button" aria-pressed={search.billable === b} onClick={() => onChange({ billable: b })}>
              {t(`reports.filters.billableChoice.${b ?? "all"}`)}
            </button>
          ))}
        </div>
      )}
      {chips.map((k) => (
        <span key={k} className="chip">
          <button type="button" className="chip-label" onClick={() => setEditing(k)}>
            <span className="muted">{t(`reports.filters.${k}`)}:</span> {names(k)}
          </button>
          <button type="button" className="chip-remove" aria-label={t("reports.filters.remove", { filter: t(`reports.filters.${k}`) })} onClick={() => onChange({ [k]: undefined })}>
            ×
          </button>
        </span>
      ))}
      {editing && (
        <FilterDialog
          title={t(`reports.filters.${editing}`)}
          options={options[editing]}
          selected={search[editing] ?? []}
          onClose={() => setEditing(null)}
          onApply={(ids) => {
            onChange({ [editing]: ids.length ? ids : undefined });
            setEditing(null);
          }}
        />
      )}
    </div>
  );
}

function FilterDialog({ title, options, selected, onClose, onApply }: { title: string; options: Option[]; selected: string[]; onClose: () => void; onApply: (ids: string[]) => void }) {
  const { t } = useTranslation();
  const [chosen, setChosen] = useState(() => new Set(selected));
  const [q, setQ] = useState("");
  const shown = useMemo(() => {
    const needle = q.trim().toLocaleLowerCase();
    return needle ? options.filter((o) => `${o.name} ${o.hint ?? ""}`.toLocaleLowerCase().includes(needle)) : options;
  }, [options, q]);
  const toggle = (id: string, on: boolean) =>
    setChosen((prev) => {
      const next = new Set(prev);
      if (on) next.add(id);
      else next.delete(id);
      return next;
    });
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={title}>
      <form
        className="stack"
        onSubmit={(e) => {
          e.preventDefault();
          onApply([...chosen]);
        }}
      >
        {options.length > 8 && (
          <input className="input" type="search" autoFocus placeholder={t("reports.filters.search")} value={q} onChange={(e) => setQ(e.target.value)} aria-label={t("reports.filters.search")} />
        )}
        <div className="filter-options">
          {shown.length === 0 && <p className="muted">{t("reports.filters.none")}</p>}
          {shown.map((o) => (
            <Checkbox
              key={o.id}
              checked={chosen.has(o.id)}
              onChange={(on) => toggle(o.id, on)}
              label={
                <>
                  {o.name}
                  {o.hint && <span className="muted"> · {o.hint}</span>}
                </>
              }
            />
          ))}
        </div>
        <DialogActions>
          {chosen.size > 0 && (
            <Button type="button" variant="ghost" onClick={() => setChosen(new Set())}>
              {t("reports.filters.clear")}
            </Button>
          )}
          <Button type="button" onClick={onClose}>
            {t("app.cancel")}
          </Button>
          <Button type="submit" variant="primary">
            {t("reports.filters.apply")}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}

/** "Export ▾": CSV or Excel, of exactly what's on screen. */
export function ExportMenu({ exports }: { exports: { label: string; path: string }[] }) {
  const { t } = useTranslation();
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  const run = async (path: string) => {
    setBusy(true);
    try {
      await downloadFile(path);
    } catch (e) {
      toast(errorInfo(e).message, "error");
    } finally {
      setBusy(false);
    }
  };
  return (
    <Menu
      trigger={
        <Button size="sm" busy={busy}>
          {t("reports.export.button")} <span aria-hidden>▾</span>
        </Button>
      }
      items={exports.map((x) => ({ label: x.label, onSelect: () => void run(x.path) }))}
    />
  );
}
