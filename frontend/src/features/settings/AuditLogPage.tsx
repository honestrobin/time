// SPDX-License-Identifier: AGPL-3.0-only
import { useInfiniteQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { Button } from "../../design";
import { api, unwrap, type Schemas } from "../../lib/api";
import { formatDateTime } from "../../lib/format";

type Entry = Schemas["AuditEntry"];

function describeDiff(diff: Entry["diff"]): string {
  if (!diff || typeof diff !== "object") return "";
  return Object.entries(diff as Record<string, [unknown, unknown]>)
    .filter(([k]) => !["id", "account_id", "created_at"].includes(k))
    .slice(0, 6)
    .map(([k, v]) => (Array.isArray(v) ? `${k}: ${fmt(v[0])} → ${fmt(v[1])}` : k))
    .join("; ");
}

function fmt(v: unknown): string {
  if (v === null || v === undefined) return "∅";
  const s = typeof v === "string" ? v : JSON.stringify(v);
  return s.length > 40 ? `${s.slice(0, 40)}…` : s;
}

export function AuditLogPage() {
  const { t } = useTranslation();
  const q = useInfiniteQuery({
    queryKey: ["audit_log"],
    queryFn: ({ pageParam }) => unwrap(api.GET("/api/v1/audit_log", { params: { query: { cursor: pageParam, limit: 50 } } })),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.next_cursor ?? undefined,
  });
  const entries = q.data?.pages.flatMap((p) => p.entries) ?? [];
  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>{t("settings.auditTitle")}</h1>
          <p className="muted">{t("settings.auditLead")}</p>
        </div>
      </header>
      <table className="ledger">
        <thead>
          <tr>
            <th>{t("settings.when")}</th>
            <th>{t("settings.who")}</th>
            <th>{t("settings.what")}</th>
            <th>{t("settings.changes")}</th>
          </tr>
        </thead>
        <tbody>
          {entries.length === 0 && !q.isLoading && (
            <tr>
              <td colSpan={4} className="muted">
                {t("settings.noAudit")}
              </td>
            </tr>
          )}
          {entries.map((e) => (
            <tr key={e.id}>
              <td style={{ whiteSpace: "nowrap" }}>{formatDateTime(e.at)}</td>
              <td>{e.actor_name ?? t("settings.system")}</td>
              <td>
                {e.action}
                {e.reason && <div className="muted">“{e.reason}”</div>}
              </td>
              <td className="muted" style={{ fontSize: "var(--text-sm)" }}>
                {describeDiff(e.diff)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {q.hasNextPage && (
        <div style={{ marginTop: 16 }}>
          <Button onClick={() => void q.fetchNextPage()} busy={q.isFetchingNextPage}>
            {t("app.loadMore")}
          </Button>
        </div>
      )}
    </div>
  );
}
