// SPDX-License-Identifier: AGPL-3.0-only
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

/** Finds keys that appear twice in one object; JSON.parse silently keeps the last one. */
function duplicateKeys(text: string): string[] {
  const dups: string[] = [];
  const stack: Set<string>[] = [];
  const path: string[] = [];
  let i = 0;
  let lastKey = "";
  const readString = () => {
    let s = "";
    i++;
    while (text[i] !== '"') {
      if (text[i] === "\\") i++;
      s += text[i++];
    }
    i++;
    return s;
  };
  while (i < text.length) {
    const c = text[i];
    if (c === "{") {
      stack.push(new Set());
      path.push(lastKey);
      i++;
    } else if (c === "}") {
      stack.pop();
      path.pop();
      i++;
    } else if (c === '"') {
      const s = readString();
      let j = i;
      while (/\s/.test(text[j])) j++;
      if (text[j] === ":") {
        const seen = stack[stack.length - 1];
        if (seen.has(s)) dups.push([...path.slice(1), s].join("."));
        seen.add(s);
        lastKey = s;
      }
    } else {
      i++;
    }
  }
  return dups;
}

describe("locale files", () => {
  const dir = join(__dirname, "en");
  for (const file of readdirSync(dir).filter((f) => f.endsWith(".json"))) {
    it(`${file} has no key twice in the same object`, () => {
      expect(duplicateKeys(readFileSync(join(dir, file), "utf8"))).toEqual([]);
    });
  }
});
