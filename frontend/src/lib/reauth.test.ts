// SPDX-License-Identifier: AGPL-3.0-only
import { describe, expect, it } from "vitest";
import { confirmIdentity, registerReauth } from "./reauth";

describe("confirmIdentity", () => {
  it("says no when no dialog is mounted", async () => {
    expect(await confirmIdentity()).toBe(false);
  });

  it("opens one dialog for requests that need it at the same time", async () => {
    const opened: ((ok: boolean) => void)[] = [];
    const unregister = registerReauth((done) => opened.push(done));
    const a = confirmIdentity();
    const b = confirmIdentity();
    expect(opened).toHaveLength(1);
    opened[0](true);
    expect(await Promise.all([a, b])).toEqual([true, true]);

    // A later request asks again.
    const c = confirmIdentity();
    expect(opened).toHaveLength(2);
    opened[1](false);
    expect(await c).toBe(false);
    unregister();
    expect(await confirmIdentity()).toBe(false);
  });
});
