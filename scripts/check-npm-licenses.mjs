#!/usr/bin/env node
// SPDX-License-Identifier: AGPL-3.0-only
// Rejects npm dependencies whose licences are incompatible with AGPL-3.0.
import { execSync } from "node:child_process";

const allowed = new Set([
  "MIT", "MIT-0", "ISC", "BSD-2-Clause", "BSD-3-Clause", "0BSD", "Apache-2.0", "Unlicense", "CC0-1.0",
  "OFL-1.1", "BlueOak-1.0.0", "Python-2.0", "CC-BY-4.0", "Zlib", "MPL-2.0", "AGPL-3.0-only", "AGPL-3.0-or-later",
  "LGPL-3.0-only", "LGPL-3.0-or-later", "GPL-3.0-only", "GPL-3.0-or-later",
]);

const report = JSON.parse(execSync("pnpm licenses list --json --prod", { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 }));
const bad = [];
for (const [license, packages] of Object.entries(report)) {
  const ids = license.replace(/[()]/g, "").split(/\s+OR\s+/);
  if (ids.some((id) => allowed.has(id.trim()))) continue;
  for (const p of packages) bad.push(`${p.name}@${p.versions?.join(",") ?? p.version}: ${license}`);
}
if (bad.length) {
  console.error("Dependencies with licences not on the allowlist:\n" + bad.join("\n"));
  process.exit(1);
}
console.log("All npm production dependencies have AGPL-compatible licences.");
