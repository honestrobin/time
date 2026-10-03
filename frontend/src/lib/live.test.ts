// SPDX-License-Identifier: AGPL-3.0-only
import { describe, expect, it } from "vitest";
import { readEvents } from "./live";

function stream(parts: string[]) {
  return new ReadableStream<Uint8Array>({
    start(c) {
      for (const p of parts) c.enqueue(new TextEncoder().encode(p));
      c.close();
    },
  });
}

describe("readEvents", () => {
  it("reads event names across chunk boundaries, and skips comments", async () => {
    const names: string[] = [];
    await readEvents(stream(["event:ready\ndata:{}\n\n:ping\n\nevent:time_", "entries\ndata:{}\n\n"]), (n) => names.push(n));
    expect(names).toEqual(["ready", "time_entries"]);
  });
});
