// SPDX-License-Identifier: AGPL-3.0-only
import { readdirSync, readFileSync } from "node:fs";
import { join, relative } from "node:path";
import { describe, expect, it } from "vitest";

/**
 * Everything the web app keeps in your browser, and what it holds. None of it says who you are or
 * follows you anywhere (the Robin's Code, article 5). A new key fails the test below until it's
 * listed here and in PROMISES.md.
 */
const KEPT: Record<string, string> = {
  "honestrobin.account": "which of your workspaces is open",
  "honestrobin.weekShape": "whether the week shows as a table or as strips",
};

const SRC = join(__dirname, "..");

function sources(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const path = join(dir, e.name);
    if (e.isDirectory()) return sources(path);
    return /\.tsx?$/.test(e.name) && !/\.test\.tsx?$/.test(e.name) ? [path] : [];
  });
}

describe("what the web app keeps in your browser", () => {
  it("is only what's listed: no cookie of its own, no other store", () => {
    const written = new Set<string>();
    for (const file of sources(SRC)) {
      const text = readFileSync(file, "utf8");
      const name = relative(SRC, file);
      expect(text, name).not.toMatch(/sessionStorage|indexedDB|cookieStore|document\.cookie\s*=[^=]/);
      for (const [, key] of text.matchAll(/localStorage\.setItem\(\s*([^,]+),/g)) {
        // A key is written as a string, or through a constant set to one in the same file.
        const literal = key.match(/^"([^"]+)"$/)?.[1] ?? text.match(new RegExp(`const ${key.trim()} = "([^"]+)"`))?.[1];
        expect(literal, `${name}: ${key}`).toBeDefined();
        written.add(literal!);
      }
    }
    expect([...written].sort()).toEqual(Object.keys(KEPT).sort());
  });
});
