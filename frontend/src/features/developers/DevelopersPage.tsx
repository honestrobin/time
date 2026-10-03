// SPDX-License-Identifier: AGPL-3.0-only
// The API reference, rendered from the OpenAPI document the server generates from its code.
import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { PageHeader } from "../../design";
import "./developers.css";

interface Schema {
  type?: string;
  format?: string;
  $ref?: string;
  items?: Schema;
  properties?: Record<string, Schema>;
  required?: string[];
  enum?: string[];
  description?: string;
  additionalProperties?: Schema | boolean;
}
interface Parameter {
  name: string;
  in: string;
  required?: boolean;
  description?: string;
  schema?: Schema;
}
interface Operation {
  tags?: string[];
  summary?: string;
  description?: string;
  operationId?: string;
  parameters?: Parameter[];
  requestBody?: { content?: Record<string, { schema?: Schema }> };
  responses?: Record<string, { description?: string; content?: Record<string, { schema?: Schema }> }>;
}
interface OpenApi {
  info: { title: string; version: string };
  tags?: { name: string; description?: string }[];
  paths: Record<string, Record<string, Operation>>;
  components?: { schemas?: Record<string, Schema> };
}

const METHODS = ["get", "post", "put", "patch", "delete"] as const;

function refName(ref: string) {
  return ref.split("/").pop() ?? ref;
}

/** A schema as a short type, e.g. `TimeEntryView[]` or `string (date)`. */
function typeLabel(s: Schema | undefined): string {
  if (!s) return "";
  if (s.$ref) return refName(s.$ref);
  if (s.type === "array") return `${typeLabel(s.items)}[]`;
  if (s.enum) return s.enum.map((e) => JSON.stringify(e)).join(" | ");
  if (s.type === "object" && s.additionalProperties && typeof s.additionalProperties === "object") return `map of ${typeLabel(s.additionalProperties)}`;
  return [s.type ?? "object", s.format ? `(${s.format})` : ""].filter(Boolean).join(" ");
}

function SchemaView({ schema, doc, depth = 0 }: { schema?: Schema; doc: OpenApi; depth?: number }) {
  if (!schema) return null;
  const target = schema.$ref ? doc.components?.schemas?.[refName(schema.$ref)] : schema.type === "array" ? schema.items : schema;
  const resolved = target?.$ref ? doc.components?.schemas?.[refName(target.$ref)] : target;
  if (!resolved?.properties || depth > 3) return <code>{typeLabel(schema)}</code>;
  return (
    <details className="schema">
      <summary>
        <code>{typeLabel(schema)}</code>
      </summary>
      <table className="schema-table">
        <tbody>
          {Object.entries(resolved.properties).map(([name, prop]) => (
            <tr key={name}>
              <td>
                <code>{name}</code>
                {resolved.required?.includes(name) ? "" : <span className="muted">?</span>}
              </td>
              <td>
                {prop.$ref || prop.items?.$ref ? <SchemaView schema={prop} doc={doc} depth={depth + 1} /> : <code>{typeLabel(prop)}</code>}
                {prop.description && <div className="muted small">{prop.description}</div>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </details>
  );
}

function Endpoint({ method, path, op, doc }: { method: string; path: string; op: Operation; doc: OpenApi }) {
  const { t } = useTranslation();
  const body = op.requestBody?.content && Object.values(op.requestBody.content)[0]?.schema;
  const ok = Object.entries(op.responses ?? {}).find(([code]) => code.startsWith("2"));
  const okSchema = ok?.[1].content && Object.values(ok[1].content)[0]?.schema;
  return (
    <article className="endpoint" id={`${method}-${path}`}>
      <h3>
        <span className={`method method-${method}`}>{method.toUpperCase()}</span> <code>{path}</code>
      </h3>
      {(op.summary || op.description) && <p>{op.description ?? op.summary}</p>}
      {op.parameters && op.parameters.length > 0 && (
        <table className="ledger params">
          <thead>
            <tr>
              <th>{t("developers.parameter")}</th>
              <th>{t("developers.in")}</th>
              <th>{t("developers.type")}</th>
            </tr>
          </thead>
          <tbody>
            {op.parameters.map((p) => (
              <tr key={`${p.in}-${p.name}`}>
                <td>
                  <code>{p.name}</code>
                  {p.required && <span className="badge">{t("developers.required")}</span>}
                  {p.description && <div className="muted small">{p.description}</div>}
                </td>
                <td>{p.in}</td>
                <td>
                  <code>{typeLabel(p.schema)}</code>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {body && (
        <div className="io">
          <h4>{t("developers.body")}</h4>
          <SchemaView schema={body} doc={doc} />
        </div>
      )}
      {ok && (
        <div className="io">
          <h4>{t("developers.response", { status: ok[0] })}</h4>
          {okSchema ? <SchemaView schema={okSchema} doc={doc} /> : <span className="muted">{ok[1].description}</span>}
        </div>
      )}
    </article>
  );
}

export function DevelopersPage() {
  const { t } = useTranslation();
  const [q, setQ] = useState("");
  const spec = useQuery({
    queryKey: ["openapi"],
    queryFn: async (): Promise<OpenApi> => {
      const res = await fetch("/v3/api-docs", { credentials: "include" });
      if (!res.ok) throw new Error(res.statusText);
      return res.json();
    },
    staleTime: Infinity,
  });
  const groups = useMemo(() => {
    const doc = spec.data;
    if (!doc) return [];
    const needle = q.trim().toLowerCase();
    const byTag = new Map<string, { method: string; path: string; op: Operation }[]>();
    for (const [path, item] of Object.entries(doc.paths)) {
      if (!path.startsWith("/api/v1/")) continue;
      for (const method of METHODS) {
        const op = item[method];
        if (!op) continue;
        if (needle && !`${method} ${path} ${op.summary ?? ""} ${op.description ?? ""}`.toLowerCase().includes(needle)) continue;
        const tag = op.tags?.[0] ?? "other";
        byTag.set(tag, [...(byTag.get(tag) ?? []), { method, path, op }]);
      }
    }
    return [...byTag.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([tag, ops]) => ({
      tag,
      description: doc.tags?.find((x) => x.name === tag)?.description,
      ops: ops.sort((a, b) => a.path.localeCompare(b.path) || METHODS.indexOf(a.method as never) - METHODS.indexOf(b.method as never)),
    }));
  }, [spec.data, q]);

  return (
    <div className="page developers">
      <PageHeader title={t("developers.title")} lead={t("developers.lead")} />
      <section className="panel stack dev-intro">
        <p>
          {t("developers.tokens")} <Link to="/settings/profile">{t("developers.tokensLink")}</Link>
        </p>
        <pre>
          <code>{`curl -H "Authorization: Bearer hrt_…" ${location.origin}/api/v1/me`}</code>
        </pre>
        <p className="muted small">{t("developers.conventions")}</p>
        <p className="small">
          <a href="/v3/api-docs" target="_blank" rel="noreferrer">
            {t("developers.openapi")}
          </a>
        </p>
      </section>
      <input className="input dev-search" type="search" placeholder={t("developers.search")} aria-label={t("developers.search")} value={q} onChange={(e) => setQ(e.target.value)} />
      {spec.error && <p className="notice notice-error">{t("developers.loadError")}</p>}
      <div className="dev-layout">
        <nav className="dev-toc" aria-label={t("developers.contents")}>
          {groups.map((g) => (
            <a key={g.tag} href={`#tag-${g.tag}`}>
              {g.tag}
            </a>
          ))}
        </nav>
        <div>
          {spec.data &&
            groups.map((g) => (
              <section key={g.tag} id={`tag-${g.tag}`} className="dev-group">
                <h2>{g.tag}</h2>
                {g.description && <p className="muted">{g.description}</p>}
                {g.ops.map((o) => (
                  <Endpoint key={`${o.method} ${o.path}`} method={o.method} path={o.path} op={o.op} doc={spec.data!} />
                ))}
              </section>
            ))}
        </div>
      </div>
    </div>
  );
}
