// SPDX-License-Identifier: AGPL-3.0-only
// The toolbar popup: sign in, the running timer, start one, and recent entries (spec §9).
import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import { ask, type Assignment, type State, type TimeEntry } from "../lib/messages";
import { CLOUD_URL, normaliseInstanceUrl, storage } from "../lib/storage";

export function Popup() {
  const [state, setState] = useState<State | null>(null);
  const [error, setError] = useState<string>();
  const refresh = useCallback(
    () =>
      ask<State>({ type: "state" }).then(
        (s) => {
          setState(s);
          setError(undefined);
        },
        (e: Error) => setError(e.message),
      ),
    [],
  );
  useEffect(() => void refresh(), [refresh]);
  // While a sign-in waits for approval, the background polls the instance; follow along.
  useEffect(() => {
    if (!state || state.signedIn || !state.pending || state.pending.error) return;
    const t = setInterval(() => void refresh(), 1500);
    return () => clearInterval(t);
  }, [state, refresh]);
  useLiveUpdates(state?.signedIn === true, refresh);

  if (!state) return <main className="popup">{error ? <p className="error">{error}</p> : <p className="muted">Loading…</p>}</main>;
  return (
    <main className="popup">
      {state.signedIn ? <Tracker state={state} refresh={refresh} /> : <SignIn state={state} onState={setState} />}
      {error && <p className="error">{error}</p>}
    </main>
  );
}

/** Server-sent events from the instance while the popup is open: a timer started elsewhere shows at once (AT-4.1). */
function useLiveUpdates(enabled: boolean, onChange: () => void) {
  useEffect(() => {
    if (!enabled) return;
    const abort = new AbortController();
    void (async () => {
      const session = await storage.session();
      if (!session) return;
      while (!abort.signal.aborted) {
        try {
          const res = await fetch(`${session.instanceUrl}/api/v1/me/events`, {
            headers: { Authorization: `Bearer ${session.token}`, "HonestRobin-Account-Id": session.accountId, Accept: "text/event-stream" },
            signal: abort.signal,
            credentials: "omit",
          });
          if (!res.ok || !res.body) throw new Error(String(res.status));
          const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
          let buffer = "";
          for (;;) {
            const { value, done } = await reader.read();
            if (done) break;
            buffer += value;
            const events = buffer.split("\n\n");
            buffer = events.pop() ?? "";
            if (events.some((e) => /^event:\s*time_entries/m.test(e))) onChange();
          }
        } catch {
          if (abort.signal.aborted) return;
        }
        await new Promise((r) => setTimeout(r, 3000));
      }
    })();
    return () => abort.abort();
  }, [enabled, onChange]);
}

function clientName() {
  const ua = navigator.userAgent;
  const browser = ua.includes("Firefox/") ? "Firefox" : ua.includes("Edg/") ? "Edge" : "Chrome";
  return `Browser extension (${browser})`;
}

function SignIn({ state, onState }: { state: State; onState: (s: State) => void }) {
  const [url, setUrl] = useState("");
  const [token, setToken] = useState("");
  const [useToken, setUseToken] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  useEffect(() => void storage.instanceUrl().then((u) => setUrl(u === CLOUD_URL ? "" : u)), []);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setError(undefined);
    let origin: string;
    try {
      origin = normaliseInstanceUrl(url || CLOUD_URL);
    } catch {
      setError("That address doesn't look right.");
      return;
    }
    setBusy(true);
    // Asked for in the click itself, as browsers require: access to a self-hosted instance.
    const permission = origin === CLOUD_URL ? Promise.resolve(true) : chrome.permissions.request({ origins: [`${origin}/*`] });
    permission
      .then((granted) => {
        if (!granted) throw new Error("Honest Robin needs access to that address to sign in.");
        return useToken
          ? ask<State>({ type: "signInWithToken", instanceUrl: origin, token })
          : ask<State>({ type: "signIn", instanceUrl: origin, clientName: clientName() });
      })
      .then(onState, (err: Error) => setError(err.message))
      .finally(() => setBusy(false));
  };

  const pending = state.pending;
  if (pending) {
    return (
      <section className="stack">
        <Header />
        {pending.error ? (
          <>
            <p className="error">{pending.error}</p>
            <button className="primary" onClick={() => void ask<State>({ type: "cancelSignIn" }).then(onState)}>
              Try again
            </button>
          </>
        ) : (
          <>
            <p>Approve this code in Honest Robin, in the tab that just opened:</p>
            <p className="code" aria-label="Your code">
              {pending.userCode}
            </p>
            <p className="muted small">Check that it matches the code on the approval page. Waiting for you…</p>
            <div className="row">
              <button onClick={() => void chrome.tabs.create({ url: pending.verificationUri })}>Open the page again</button>
              <button onClick={() => void ask<State>({ type: "cancelSignIn" }).then(onState)}>Cancel</button>
            </div>
          </>
        )}
      </section>
    );
  }
  return (
    <form className="stack" onSubmit={submit}>
      <Header />
      <p className="muted">Sign in to track time from your browser and from Jira, Asana, GitHub, Linear and Trello.</p>
      <label>
        Your Honest Robin address
        <input value={url} onChange={(e) => setUrl(e.target.value)} placeholder="time.honestrobin.com" inputMode="url" autoComplete="url" />
        <span className="hint">Leave empty for Honest Robin Cloud, or enter your own instance.</span>
      </label>
      {useToken && (
        <label>
          Personal access token
          <input value={token} onChange={(e) => setToken(e.target.value)} placeholder="hrt_…" autoComplete="off" />
          <span className="hint">Create one under Profile in Honest Robin.</span>
        </label>
      )}
      {error && <p className="error">{error}</p>}
      <button className="primary" type="submit" disabled={busy || (useToken && !token.trim())}>
        {busy ? "Signing in…" : useToken ? "Sign in with the token" : "Sign in"}
      </button>
      <button type="button" className="link" onClick={() => setUseToken(!useToken)}>
        {useToken ? "Sign in in the browser instead" : "Use a token instead"}
      </button>
    </form>
  );
}

function Header({ account, onSignOut, instanceUrl }: { account?: string; onSignOut?: () => void; instanceUrl?: string }) {
  return (
    <header className="header">
      <strong className="brand">
        Honest Robin <span>Time</span>
      </strong>
      {account && (
        <span className="account" title={account}>
          {account}
        </span>
      )}
      {instanceUrl && (
        <button className="link" onClick={() => void chrome.tabs.create({ url: `${instanceUrl}/time` })}>
          Open
        </button>
      )}
      {onSignOut && (
        <button className="link" onClick={onSignOut}>
          Sign out
        </button>
      )}
    </header>
  );
}

function elapsed(entry: TimeEntry, now: number) {
  const seconds = Math.floor((entry.duration_seconds ?? 0) + (entry.timer_started_at ? Math.max(0, (now - Date.parse(entry.timer_started_at)) / 1000) : 0));
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  return `${h}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
}

function Tracker({ state, refresh }: { state: State; refresh: () => Promise<void> }) {
  const [assignments, setAssignments] = useState<Assignment[] | null>(null);
  const [recent, setRecent] = useState<TimeEntry[]>([]);
  const [error, setError] = useState<string>();
  const [now, setNow] = useState(Date.now());
  const running = state.running;

  useEffect(() => {
    void ask<Assignment[]>({ type: "assignments" }).then(setAssignments, (e: Error) => setError(e.message));
  }, []);
  useEffect(() => {
    void ask<TimeEntry[]>({ type: "recent" }).then(setRecent, () => undefined);
  }, [running?.id]);
  useEffect(() => {
    if (!running) return;
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, [running]);

  const act = (p: Promise<unknown>) => p.then(() => refresh(), (e: Error) => setError(e.message));
  const start = (projectId: string, taskId: string, notes: string) => act(ask({ type: "start", input: { projectId, taskId, notes } }));

  return (
    <section className="stack">
      <Header account={state.accountName} instanceUrl={state.instanceUrl} onSignOut={() => void ask<State>({ type: "signOut" }).then(() => refresh())} />
      {running ? (
        <div className="running" aria-live="polite">
          <div className="what">
            <strong>{running.project.name}</strong>
            <span>{running.task.name}</span>
            {running.notes && <span className="notes">{running.notes}</span>}
          </div>
          <span className="clock">{elapsed(running, now)}</span>
          <button className="stop" onClick={() => void act(ask({ type: "stop", id: running.id }))}>
            Stop
          </button>
        </div>
      ) : (
        assignments && <StartForm assignments={assignments} initial={recent[0]} onStart={start} />
      )}
      {error && <p className="error">{error}</p>}
      {recent.length > 0 && (
        <div>
          <h2>Recent</h2>
          <ul className="recent">
            {recent.map((e) => (
              <li key={e.id}>
                <div className="what">
                  <span>{e.notes || e.task.name}</span>
                  <span className="muted small">
                    {e.project.name}
                    {e.client ? ` · ${e.client.name}` : ""}
                  </span>
                </div>
                <button className="icon" aria-label={`Start ${e.notes || e.task.name} again`} title="Start again" onClick={() => void start(e.project.id, e.task.id, e.notes ?? "")}>
                  ▶
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}

function StartForm({ assignments, initial, onStart }: { assignments: Assignment[]; initial?: TimeEntry; onStart: (p: string, t: string, n: string) => Promise<unknown> }) {
  const [filter, setFilter] = useState("");
  const [projectId, setProjectId] = useState("");
  const [taskId, setTaskId] = useState("");
  const [notes, setNotes] = useState("");
  const [busy, setBusy] = useState(false);
  const shown = useMemo(() => {
    const f = filter.trim().toLowerCase();
    return f ? assignments.filter((a) => `${a.project_name} ${a.project_code ?? ""} ${a.client?.name ?? ""}`.toLowerCase().includes(f)) : assignments;
  }, [assignments, filter]);
  // Start from the most recent project and task.
  useEffect(() => {
    if (projectId) return;
    const a = assignments.find((x) => x.project_id === initial?.project.id) ?? assignments[0];
    if (!a) return;
    setProjectId(a.project_id);
    setTaskId(a.tasks.find((t) => t.task_id === initial?.task.id)?.task_id ?? a.tasks[0]?.task_id ?? "");
  }, [assignments, initial, projectId]);
  const project = assignments.find((a) => a.project_id === projectId);
  if (assignments.length === 0) return <p className="muted">You aren't on any projects yet. Ask your admin to add you to one.</p>;
  return (
    <form
      className="stack tight"
      onSubmit={(e) => {
        e.preventDefault();
        setBusy(true);
        void onStart(projectId, taskId, notes.trim()).finally(() => setBusy(false));
      }}
    >
      {assignments.length > 8 && <input type="search" placeholder="Find a project or client" value={filter} onChange={(e) => setFilter(e.target.value)} aria-label="Find a project or client" />}
      <label>
        Project
        <select
          value={projectId}
          onChange={(e) => {
            const a = assignments.find((x) => x.project_id === e.target.value);
            setProjectId(e.target.value);
            setTaskId(a?.tasks[0]?.task_id ?? "");
          }}
        >
          {shown.map((a) => (
            <option key={a.project_id} value={a.project_id}>
              {a.client ? `${a.client.name} › ` : ""}
              {a.project_name}
            </option>
          ))}
        </select>
      </label>
      <label>
        Task
        <select value={taskId} onChange={(e) => setTaskId(e.target.value)}>
          {(project?.tasks ?? []).map((t) => (
            <option key={t.task_id} value={t.task_id}>
              {t.name}
            </option>
          ))}
        </select>
      </label>
      <label>
        Notes
        <input value={notes} onChange={(e) => setNotes(e.target.value)} placeholder="What are you working on?" />
      </label>
      <button className="primary" type="submit" disabled={busy || !projectId || !taskId}>
        Start timer
      </button>
    </form>
  );
}
