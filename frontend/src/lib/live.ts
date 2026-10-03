// SPDX-License-Identifier: AGPL-3.0-only
// Live updates (spec §9, AT-4.1): a timer started in the browser extension, on the phone or in
// another tab shows here at once. The server streams an event whenever one of the person's time
// entries changes; the visible tab listens (hidden tabs drop the stream, so many open tabs don't
// tie up connections) and refreshes what shows time.
import { useEffect } from "react";
import { authHeaders } from "./api";

/** Parses a server-sent event stream; calls [onEvent] with each event's name. */
export async function readEvents(body: ReadableStream<Uint8Array>, onEvent: (name: string) => void): Promise<void> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  for (;;) {
    const { value, done } = await reader.read();
    if (done) return;
    buffer += decoder.decode(value, { stream: true });
    const chunks = buffer.split(/\r?\n\r?\n/);
    buffer = chunks.pop() ?? "";
    for (const chunk of chunks) {
      const name = /^event:\s*(.+)$/m.exec(chunk)?.[1]?.trim();
      if (name) onEvent(name);
    }
  }
}

export function useLiveTimeEntries(onChange: () => void) {
  useEffect(() => {
    let abort: AbortController | null = null;
    let retry = 0;
    let timer = 0;

    const connect = async () => {
      if (document.visibilityState !== "visible" || abort) return;
      const controller = new AbortController();
      abort = controller;
      try {
        const res = await fetch("/api/v1/me/events", { headers: { ...authHeaders(), Accept: "text/event-stream" }, credentials: "include", signal: controller.signal });
        if (!res.ok || !res.body) throw new Error(String(res.status));
        retry = 0;
        await readEvents(res.body, (name) => {
          // "ready" after (re)connecting: catch up on anything missed while away.
          if (name === "time_entries" || name === "ready") onChange();
        });
      } catch {
        // Dropped, offline or signed out: try again below.
      }
      if (abort !== controller) return; // closed on purpose
      abort = null;
      retry = Math.min(retry + 1, 6);
      timer = window.setTimeout(() => void connect(), 1000 * 2 ** retry);
    };
    const onVisibility = () => {
      if (document.visibilityState === "visible") {
        void connect();
      } else {
        abort?.abort();
        abort = null;
        window.clearTimeout(timer);
      }
    };
    document.addEventListener("visibilitychange", onVisibility);
    void connect();
    return () => {
      document.removeEventListener("visibilitychange", onVisibility);
      window.clearTimeout(timer);
      const a = abort;
      abort = null;
      a?.abort();
    };
  }, [onChange]);
}
