// SPDX-License-Identifier: AGPL-3.0-only
// Recognising an item (an issue, a task, a card) on a supported site, from the selector config.

export interface Anchor {
  selector: string;
  placement: "append" | "prepend" | "before" | "after";
}

export interface SiteConfig {
  source: string;
  name: string;
  /** Host names; "*.atlassian.net" matches any subdomain. */
  hosts: string[];
  /** The first that matches gives the groups {1}, {2}…: a regex on the path, or a query parameter. */
  match: ({ path: string } | { param: string })[];
  id: string;
  /** Items in one workspace share the remembered project and task. */
  workspace: string;
  workspaceFrom?: { selector: string; attribute: string; pattern: string };
  /** Canonical link; by default the page address up to the matched path. */
  url?: string;
  title: string[];
  anchor: Anchor[];
}

export interface SelectorConfig {
  version: number;
  sites: SiteConfig[];
}

/** An item the person can track time on, as stored in a time entry's external_reference. */
export interface Item {
  source: string;
  id: string;
  /** The workspace (repository, Jira site, Linear team…): the entry's external group. */
  workspace: string;
  url: string;
  title: string;
}

export interface PageLocation {
  host: string;
  pathname: string;
  search: string;
  origin: string;
}

function hostMatches(pattern: string, host: string): boolean {
  if (pattern.startsWith("*.")) return host.endsWith(pattern.slice(1)) && host.length > pattern.length - 1;
  return host === pattern;
}

export function siteFor(config: SelectorConfig, host: string): SiteConfig | undefined {
  return config.sites.find((s) => s.hosts.some((h) => hostMatches(h, host)));
}

/** The groups of the first matching rule, and the part of the path it matched. */
function matchGroups(site: SiteConfig, loc: PageLocation): { groups: string[]; matched: string } | null {
  for (const rule of site.match) {
    if ("path" in rule) {
      const m = new RegExp(rule.path).exec(loc.pathname);
      if (m) return { groups: m.slice(1), matched: m[0] };
    } else {
      const value = new URLSearchParams(loc.search).get(rule.param);
      if (value) return { groups: [value], matched: loc.pathname };
    }
  }
  return null;
}

function fill(template: string, groups: string[], host: string): string {
  return template.replace(/\{(\d+|host)\}/g, (_, k: string) => (k === "host" ? host : (groups[Number(k) - 1] ?? "")));
}

function text(el: Element | null): string {
  if (!el) return "";
  // Never what the page hides: a config naming a hidden or password field gets nothing from it.
  if (el instanceof HTMLInputElement && (el.type === "hidden" || el.type === "password")) return "";
  const value = el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement ? el.value : el.textContent;
  return (value ?? "").replace(/\s+/g, " ").trim();
}

/** The item shown on this page, or null when the page shows none (yet). */
export function itemAt(site: SiteConfig, loc: PageLocation, doc: Document): Item | null {
  const m = matchGroups(site, loc);
  if (!m) return null;
  const title = site.title.map((s) => text(doc.querySelector(s))).find((t) => t.length > 0);
  if (!title) return null;
  let workspace = fill(site.workspace, m.groups, loc.host);
  if (site.workspaceFrom) {
    const raw = doc.querySelector(site.workspaceFrom.selector)?.getAttribute(site.workspaceFrom.attribute) ?? "";
    const w = new RegExp(site.workspaceFrom.pattern).exec(raw)?.[1];
    if (w) workspace = w;
  }
  return {
    source: site.source,
    id: fill(site.id, m.groups, loc.host),
    workspace,
    url: site.url ? fill(site.url, m.groups, loc.host) : loc.origin + m.matched,
    title: title.slice(0, 500),
  };
}

/** Where the button goes on this page: the first anchor present. */
export function anchorIn(site: SiteConfig, doc: Document): { element: Element; placement: Anchor["placement"] } | null {
  for (const a of site.anchor) {
    const element = doc.querySelector(a.selector);
    if (element) return { element, placement: a.placement };
  }
  return null;
}

/** How far ahead of the bundled config an instance's config may say it is. */
const MAX_VERSION_STEP = 1000;

/**
 * The instance's config when it is newer than the bundled one and safe to run in other sites'
 * pages; otherwise the bundled one. An instance (or anyone between it and the browser) chooses
 * this config, so it may only adjust what the bundled config already does: the same sites and
 * hosts, short selectors, links rather than other attributes, and regexes that can't take
 * seconds to run.
 */
export function newer(bundled: SelectorConfig, candidate: SelectorConfig | null | undefined): SelectorConfig {
  return candidate && acceptable(bundled, candidate) ? candidate : bundled;
}

function acceptable(bundled: SelectorConfig, c: SelectorConfig): boolean {
  if (typeof c !== "object" || !Array.isArray(c.sites) || c.sites.length === 0) return false;
  if (!Number.isInteger(c.version) || c.version <= bundled.version || c.version > bundled.version + MAX_VERSION_STEP) return false;
  return c.sites.every((site) => siteAcceptable(bundled, site));
}

function siteAcceptable(bundled: SelectorConfig, s: SiteConfig): boolean {
  const known = bundled.sites.find((b) => b.source === s.source);
  if (!known || !Array.isArray(s.hosts) || !s.hosts.every((h) => known.hosts.includes(h))) return false;
  if (!Array.isArray(s.match) || !s.match.every((m) => ("path" in m ? safePattern(m.path) : short(m.param)))) return false;
  if (!Array.isArray(s.title) || !Array.isArray(s.anchor)) return false;
  if (s.workspaceFrom && (s.workspaceFrom.attribute !== "href" || !safePattern(s.workspaceFrom.pattern) || !short(s.workspaceFrom.selector))) return false;
  return [s.id, s.workspace, s.url ?? "", ...s.title, ...s.anchor.map((a) => a.selector)].every(short);
}

function short(x: unknown): boolean {
  return typeof x === "string" && x.length <= 300;
}

/**
 * A short regex with no nested groups, no backreferences, and no repeated group that holds a
 * repeat or a choice: the shapes that make matching take exponential time.
 */
export function safePattern(pattern: unknown): boolean {
  if (typeof pattern !== "string" || pattern.length > 200) return false;
  try {
    new RegExp(pattern);
  } catch {
    return false;
  }
  let depth = 0;
  let inClass = false;
  let risky = false; // the open group holds a quantifier or an alternation
  for (let i = 0; i < pattern.length; i++) {
    const c = pattern[i];
    if (c === "\\") {
      if (/[1-9k]/.test(pattern[i + 1] ?? "")) return false; // backreference
      i++;
      continue;
    }
    if (inClass) {
      if (c === "]") inClass = false;
      continue;
    }
    if (c === "[") inClass = true;
    else if (c === "(") {
      if (++depth > 1) return false;
      risky = false;
    } else if (c === ")") {
      depth--;
      if (risky && /[*+?{]/.test(pattern[i + 1] ?? "")) return false;
    } else if (depth > 0 && /[*+?{|]/.test(c) && !(c === "?" && pattern[i - 1] === "(")) risky = true;
  }
  return true;
}
