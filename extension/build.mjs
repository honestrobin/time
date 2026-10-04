// SPDX-License-Identifier: AGPL-3.0-only
// Builds the extension for Chrome and Firefox (Manifest V3) into dist/<browser>/.
//   node build.mjs          release builds
//   node build.mjs --e2e    also allows http://localhost, for the end-to-end tests
import { build } from "esbuild";
import { cpSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";

const e2e = process.argv.includes("--e2e");
const pkg = JSON.parse(readFileSync("package.json", "utf8"));
const selectors = JSON.parse(readFileSync("src/selectors.json", "utf8"));

// The sites the "Track time" button appears on, from the selector config.
const sitePatterns = [...new Set(selectors.sites.flatMap((s) => s.hosts.map((h) => `https://${h}/*`)))];

function manifest(browser) {
  return {
    manifest_version: 3,
    name: "Honest Robin: Time",
    short_name: "Honest Robin",
    description: "Track time from your browser, and from Jira, Asana, GitHub, Linear and Trello.",
    version: pkg.version,
    icons: { 16: "icons/16.png", 32: "icons/32.png", 48: "icons/48.png", 128: "icons/128.png" },
    action: { default_popup: "popup.html", default_title: "Honest Robin: Time", default_icon: { 16: "icons/16.png", 32: "icons/32.png" } },
    background: browser === "firefox" ? { scripts: ["background.js"] } : { service_worker: "background.js" },
    permissions: ["storage", "alarms"],
    // Honest Robin Cloud; a self-hosted instance is asked for when signing in.
    host_permissions: ["https://time.honestrobin.com/*", ...(e2e ? ["http://localhost/*", "http://127.0.0.1/*"] : [])],
    optional_host_permissions: ["https://*/*", "http://*/*"],
    content_scripts: [{ matches: sitePatterns, js: ["content.js"], run_at: "document_idle" }],
    ...(browser === "firefox"
      ? { browser_specific_settings: { gecko: { id: "time@honestrobin.com", strict_min_version: "128.0" } } }
      : { minimum_chrome_version: "120" }),
  };
}

for (const browser of ["chrome", "firefox"]) {
  const out = `dist/${browser}`;
  rmSync(out, { recursive: true, force: true });
  mkdirSync(out, { recursive: true });
  await build({
    entryPoints: { background: "src/background.ts", content: "src/content/content.ts", popup: "src/popup/main.tsx" },
    outdir: out,
    bundle: true,
    format: "iife",
    target: ["chrome120", "firefox128"],
    minify: !e2e,
    sourcemap: e2e ? "inline" : false,
    define: { "process.env.NODE_ENV": JSON.stringify(e2e ? "development" : "production") },
    loader: { ".woff2": "file" },
    logLevel: "warning",
  });
  cpSync("src/popup/popup.html", `${out}/popup.html`);
  cpSync("icons", `${out}/icons`, { recursive: true });
  writeFileSync(`${out}/manifest.json`, JSON.stringify(manifest(browser), null, 2));
}
console.log(`Built dist/chrome and dist/firefox${e2e ? " (e2e)" : ""}`);
