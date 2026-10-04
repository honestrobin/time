// SPDX-License-Identifier: AGPL-3.0-only
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

// The app can't reach docs/ when it's built, so it has copies. gen.py writes both.
const app = join(__dirname, "robin");
const docs = join(__dirname, "../../../docs/brand/mascot");

describe("the robin", () => {
  it("is the same bird in the app and in the brand folder", () => {
    const files = readdirSync(app).filter((f) => f.endsWith(".svg"));
    expect(files.sort()).toEqual(["robin-face.svg", "robin-nest.svg", "robin-notes.svg", "robin-promise.svg", "robin-time.svg"]);
    for (const f of files) expect(readFileSync(join(app, f), "utf8"), f).toBe(readFileSync(join(docs, f), "utf8"));
    expect(readFileSync(join(__dirname, "../../public/favicon.svg"), "utf8")).toBe(readFileSync(join(docs, "robin-face.svg"), "utf8"));
  });
});
