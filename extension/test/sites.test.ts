// SPDX-License-Identifier: AGPL-3.0-only
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import selectors from "../src/selectors.json";
import { anchorIn, itemAt, newer, siteFor, type SelectorConfig } from "../src/lib/sites";

const config = selectors as SelectorConfig;

/** Loads a fixture page into the document; returns its address as the item code sees it. */
function load(name: string) {
  const html = readFileSync(`test/fixtures/${name}.html`, "utf8");
  document.documentElement.innerHTML = new DOMParser().parseFromString(html, "text/html").documentElement.innerHTML;
  const url = new URL(document.querySelector("meta[name=fixture-url]")!.getAttribute("content")!);
  return { host: url.host, pathname: url.pathname, search: url.search, origin: url.origin };
}

describe("recognising items on the five sites (AT-4.2)", () => {
  const cases = {
    "github-issue": { source: "github", id: "acme/rocket#42", workspace: "acme/rocket", url: "https://github.com/acme/rocket/issues/42", title: "Login button misaligned on Safari" },
    "github-pr": { source: "github", id: "acme/rocket#7", workspace: "acme/rocket", url: "https://github.com/acme/rocket/pull/7", title: "Add dark mode to settings" },
    jira: { source: "jira", id: "ROCK-128", workspace: "acme.atlassian.net", url: "https://acme.atlassian.net/browse/ROCK-128", title: "Payment webhook retries" },
    "jira-board": { source: "jira", id: "ROCK-7", workspace: "acme.atlassian.net", url: "https://acme.atlassian.net/browse/ROCK-7", title: "Rate limit the export endpoint" },
    asana: { source: "asana", id: "1200000000000003", workspace: "1200000000000001", url: "https://app.asana.com/0/0/1200000000000003", title: "Write onboarding copy" },
    linear: { source: "linear", id: "ENG-311", workspace: "acme", url: "https://linear.app/acme/issue/ENG-311", title: "Sync stalls on large boards" },
    trello: { source: "trello", id: "AbC123xy", workspace: "BoArD123", url: "https://trello.com/c/AbC123xy", title: "Design review" },
  };
  for (const [fixture, expected] of Object.entries(cases)) {
    it(fixture, () => {
      const loc = load(fixture);
      const site = siteFor(config, loc.host)!;
      expect(site.source).toBe(expected.source);
      expect(itemAt(site, loc, document)).toEqual(expected);
      expect(anchorIn(site, document)).not.toBeNull();
    });
  }

  it("ignores pages that aren't items, and other sites", () => {
    const loc = load("github-issue");
    const site = siteFor(config, "github.com")!;
    expect(itemAt(site, { ...loc, pathname: "/acme/rocket/pulls" }, document)).toBeNull();
    expect(siteFor(config, "example.com")).toBeUndefined();
    expect(siteFor(config, "atlassian.net")).toBeUndefined();
    expect(siteFor(config, "evil-atlassian.net")).toBeUndefined();
  });
});

describe("selector configs", () => {
  it("uses the newer of the bundled and the instance's, never a broken one", () => {
    const newerOne = { ...config, version: config.version + 1 };
    expect(newer(config, newerOne)).toBe(newerOne);
    expect(newer(newerOne, config)).toBe(newerOne);
    expect(newer(config, { version: 99, sites: [] })).toBe(config);
    expect(newer(config, null)).toBe(config);
  });
});
