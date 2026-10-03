// SPDX-License-Identifier: AGPL-3.0-only
import { useInfiniteQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, Dialog, DialogActions, EmptyState, LoadingRow, SelectField, useToast } from "../../design";
import { api, errorInfo, unwrap, type Schemas } from "../../lib/api";
import { formatDate, formatDuration, formatMoney, type DurationStyle } from "../../lib/format";
import type { ProjectChoice } from "./options";
import type { Range } from "./period";
import { filterQuery, type ReportSearch } from "./search";
import { useTimeReport } from "./TimeReport";

type Entry = Schemas["TimeEntryView"];
type Bulk = Schemas["BulkTimeEntryUpdate"];

const editable = (e: Entry) => !e.is_locked && !e.invoice_id && e.approval_state !== "approved";

interface Props {
  search: ReportSearch;
  range: Range;
  durationStyle: DurationStyle;
  canSeeRates: boolean;
  projects: ProjectChoice[];
}

/** Every entry, oldest first. Select entries to move them or change billable in one go. */
export function DetailedReport({ search, range, durationStyle, canSeeRates, projects }: Props) {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const query = filterQuery(search, range);
  const list = useInfiniteQuery({
    queryKey: ["reports", "detailed", query],
    queryFn: ({ pageParam }) => unwrap(api.GET("/api/v1/reports/detailed", { params: { query: { ...query, cursor: pageParam, limit: 200 } } })),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.next_cursor ?? undefined,
  });
  const totals = useTimeReport(search, range, "project").data;
  const rows = useMemo(() => list.data?.pages.flatMap((p) => p.data) ?? [], [list.data]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [moving, setMoving] = useState(false);
  const selectable = rows.filter(editable);
  const allSelected = selectable.length > 0 && selectable.every((e) => selected.has(e.id));

  const bulk = useMutation({
    mutationFn: (body: Omit<Bulk, "ids">) => unwrap(api.POST("/api/v1/time_entries/bulk", { body: { ...body, ids: [...selected] } })),
    onSuccess: async (res) => {
      setSelected(new Set());
      setMoving(false);
      const skipped = res.skipped.length;
      toast(skipped ? t("reports.bulk.doneSkipped", { count: res.updated, skipped }) : t("reports.bulk.done", { count: res.updated }), skipped ? "error" : "ok");
      await Promise.all([
        qc.invalidateQueries({ queryKey: ["reports"] }),
        qc.invalidateQueries({ queryKey: ["time_entries"] }),
        qc.invalidateQueries({ queryKey: ["week"] }),
      ]);
    },
    onError: (e) => toast(errorInfo(e).message, "error"),
  });

  const toggle = (id: string, on: boolean) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (on) next.add(id);
      else next.delete(id);
      return next;
    });

  const cols = canSeeRates ? 8 : 7;
  return (
    <section aria-label={t("reports.tabs.detailed")}>
      {totals && (
        <p className="report-line">
          {t("reports.detailed.summary", { count: totals.entry_count, hours: formatDuration(totals.seconds, durationStyle) })}
        </p>
      )}
      {list.error ? (
        <p className="notice notice-error">{errorInfo(list.error).message}</p>
      ) : !list.isLoading && rows.length === 0 ? (
        <EmptyState title={t("reports.empty.title")} body={t("reports.empty.body")} />
      ) : (
        <div className="table-scroll">
          <table className="ledger report-table detailed-table">
            <thead>
              <tr>
                <th className="col-check">
                  <input
                    type="checkbox"
                    aria-label={t("reports.bulk.selectAll")}
                    checked={allSelected}
                    disabled={selectable.length === 0}
                    onChange={(e) => setSelected(e.target.checked ? new Set(selectable.map((x) => x.id)) : new Set())}
                  />
                </th>
                <th>{t("reports.col.date")}</th>
                <th>{t("reports.col.person")}</th>
                <th>{t("reports.col.project")}</th>
                <th>{t("reports.col.notes")}</th>
                <th className="num">{t("reports.col.hours")}</th>
                {canSeeRates && <th className="num">{t("reports.col.amount")}</th>}
                <th>{t("reports.col.status")}</th>
              </tr>
            </thead>
            <tbody>
              {list.isLoading && <LoadingRow colSpan={cols} />}
              {rows.map((e) => (
                <tr key={e.id} className={selected.has(e.id) ? "is-selected" : undefined}>
                  <td className="col-check">
                    <input
                      type="checkbox"
                      aria-label={t("reports.bulk.select", { date: formatDate(e.spent_date, "short"), project: e.project.name })}
                      checked={selected.has(e.id)}
                      disabled={!editable(e)}
                      onChange={(ev) => toggle(e.id, ev.target.checked)}
                    />
                  </td>
                  <td className="nowrap">{formatDate(e.spent_date, "short")}</td>
                  <td>{e.person.name}</td>
                  <td>
                    {e.project.name} <span className="muted">· {e.task.name}</span>
                    <div className="muted small">{e.client.name}</div>
                  </td>
                  <td className="notes-cell">{e.notes ?? <span className="muted">—</span>}</td>
                  <td className="num">
                    {formatDuration(e.rounded_seconds, durationStyle)}
                    {e.rounded_seconds !== e.duration_seconds && <div className="muted small">{formatDuration(e.duration_seconds, durationStyle)}</div>}
                  </td>
                  {canSeeRates && <td className="num">{e.billable ? formatMoney(e.billable_amount, e.currency) : <span className="muted">{t("reports.nonBillable")}</span>}</td>}
                  <td>
                    <EntryStatus entry={e} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {list.hasNextPage && (
        <Button onClick={() => list.fetchNextPage()} busy={list.isFetchingNextPage} style={{ marginTop: 16 }}>
          {t("app.loadMore")}
        </Button>
      )}

      {selected.size > 0 && (
        <div className="bulk-bar" role="region" aria-label={t("reports.bulk.label")}>
          <strong>{t("reports.bulk.selected", { count: selected.size })}</strong>
          <Button size="sm" onClick={() => setMoving(true)}>
            {t("reports.bulk.move")}
          </Button>
          <Button size="sm" onClick={() => bulk.mutate({ billable: true })} busy={bulk.isPending && bulk.variables?.billable === true}>
            {t("reports.bulk.billable")}
          </Button>
          <Button size="sm" onClick={() => bulk.mutate({ billable: false })} busy={bulk.isPending && bulk.variables?.billable === false}>
            {t("reports.bulk.nonBillable")}
          </Button>
          <Button size="sm" variant="ghost" onClick={() => setSelected(new Set())}>
            {t("reports.bulk.clear")}
          </Button>
        </div>
      )}
      {moving && (
        <MoveDialog
          count={selected.size}
          projects={projects}
          busy={bulk.isPending}
          onClose={() => setMoving(false)}
          onMove={(projectId, taskId) => bulk.mutate({ project_id: projectId, task_id: taskId })}
        />
      )}
    </section>
  );
}

function EntryStatus({ entry }: { entry: Entry }) {
  const { t } = useTranslation();
  if (entry.invoice_id) return <span className="badge badge-ok">{t("reports.status.invoiced")}</span>;
  if (entry.is_running) return <span className="badge badge-warn">{t("reports.status.running")}</span>;
  if (entry.approval_state === "approved") return <span className="badge">{t("reports.status.approved")}</span>;
  if (entry.approval_state === "submitted") return <span className="badge">{t("reports.status.submitted")}</span>;
  if (entry.is_locked) return <span className="badge">{t("reports.status.locked")}</span>;
  return null;
}

function MoveDialog({ count, projects, busy, onClose, onMove }: { count: number; projects: ProjectChoice[]; busy: boolean; onClose: () => void; onMove: (projectId: string, taskId: string) => void }) {
  const { t } = useTranslation();
  const [projectId, setProjectId] = useState("");
  const [taskId, setTaskId] = useState("");
  const project = projects.find((p) => p.id === projectId);
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={t("reports.bulk.moveTitle", { count })} description={t("reports.bulk.moveHint")}>
      <form
        className="stack"
        onSubmit={(e) => {
          e.preventDefault();
          if (projectId && taskId) onMove(projectId, taskId);
        }}
      >
        <SelectField
          label={t("reports.col.project")}
          value={projectId}
          onChange={(v) => {
            setProjectId(v);
            const tasks = projects.find((p) => p.id === v)?.tasks ?? [];
            setTaskId(tasks.length === 1 ? tasks[0].id : "");
          }}
          placeholder={t("reports.bulk.chooseProject")}
          options={projects.map((p) => ({ value: p.id, label: p.hint ? `${p.name} · ${p.hint}` : p.name }))}
        />
        <SelectField
          label={t("reports.col.task")}
          value={taskId}
          onChange={setTaskId}
          placeholder={t("reports.bulk.chooseTask")}
          options={(project?.tasks ?? []).map((x) => ({ value: x.id, label: x.name }))}
          disabled={!project}
        />
        <DialogActions>
          <Button onClick={onClose}>{t("app.cancel")}</Button>
          <Button type="submit" variant="primary" disabled={!projectId || !taskId} busy={busy}>
            {t("reports.bulk.moveConfirm", { count })}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
