// SPDX-License-Identifier: AGPL-3.0-only
// The "Track time" button on Jira, Asana, GitHub, Linear and Trello (spec §9). One script for all
// sites, driven by the selector config; the button lives in a shadow root so the page's styles
// can't reach it, and follows single-page navigation by watching the DOM. (No custom element
// class: content scripts have no customElements registry in Chrome.)
import bundled from "../selectors.json";
import { ask, type Assignment, type State, type TimeEntry } from "../lib/messages";
import { anchorIn, itemAt, newer, siteFor, type Item, type SelectorConfig, type SiteConfig } from "../lib/sites";
import { storage } from "../lib/storage";
import { STYLES } from "./styles";

const TAG = "honestrobin-track";

async function main() {
  const config = newer(bundled as SelectorConfig, await storage.selectors().catch(() => undefined));
  const site = siteFor(config, location.host);
  if (!site) return;
  let pending = 0;
  const observer = new MutationObserver(() => {
    clearTimeout(pending);
    pending = window.setTimeout(() => place(site), 250);
  });
  observer.observe(document.documentElement, { childList: true, subtree: true });
  place(site);
}

function key(item: Item) {
  return `${item.source}:${item.id}`;
}

let current: TrackButton | null = null;

function place(site: SiteConfig) {
  const item = itemAt(site, location, document);
  const anchor = anchorIn(site, document);
  if (!item || !anchor) {
    current?.remove();
    current = null;
    return;
  }
  if (current?.host.isConnected && current.key === key(item)) {
    current.item = item; // the title may have been edited
    return;
  }
  current?.remove();
  current = new TrackButton(item);
  const { element, placement } = anchor;
  if (placement === "append") element.append(current.host);
  else if (placement === "prepend") element.prepend(current.host);
  else if (placement === "before") element.before(current.host);
  else element.after(current.host);
  current.connect();
}

function matches(entry: TimeEntry | null, item: Item) {
  return !!entry?.external_reference && entry.external_reference.source === item.source && entry.external_reference.id === item.id;
}

class TrackButton {
  readonly host = document.createElement(TAG);
  readonly key: string;
  private root = this.host.attachShadow({ mode: "open" });
  private running: TimeEntry | null = null;
  private panel: HTMLElement | null = null;
  private outside = (e: MouseEvent) => {
    if (this.panel && !e.composedPath().includes(this.host)) this.closePanel();
  };

  constructor(public item: Item) {
    this.key = key(item);
    this.host.dataset.key = this.key;
  }

  connect() {
    this.render();
    void this.refresh();
    document.addEventListener("mousedown", this.outside, true);
  }

  remove() {
    document.removeEventListener("mousedown", this.outside, true);
    this.host.remove();
  }

  private async refresh() {
    try {
      const state = await ask<State>({ type: "state" });
      this.running = state.running;
    } catch {
      this.running = null;
    }
    this.render();
  }

  private render() {
    const tracking = matches(this.running, this.item);
    this.root.innerHTML = `<style>${STYLES}</style>`;
    const button = document.createElement("button");
    button.type = "button";
    button.className = tracking ? "track running" : "track";
    button.innerHTML = `<span class="dot" aria-hidden="true"></span><span></span>`;
    button.lastElementChild!.textContent = tracking ? "Stop timer" : "Track time";
    button.title = tracking ? "Stop the Honest Robin timer for this item" : `Track time on this ${this.item.source === "github" ? "issue" : "item"} with Honest Robin`;
    button.addEventListener("click", () => void (tracking ? this.stop() : this.openPanel()));
    this.root.append(button);
    if (this.panel) this.root.append(this.panel);
  }

  private async stop() {
    if (!this.running) return;
    try {
      await ask<TimeEntry>({ type: "stop", id: this.running.id });
      this.running = null;
    } catch (e) {
      this.flash((e as Error).message);
    }
    this.render();
  }

  private closePanel() {
    this.panel?.remove();
    this.panel = null;
  }

  private async openPanel() {
    if (this.panel) return this.closePanel();
    const panel = document.createElement("div");
    panel.className = "panel";
    panel.setAttribute("role", "dialog");
    panel.setAttribute("aria-label", "Track time with Honest Robin");
    panel.textContent = "Loading…";
    this.panel = panel;
    this.root.append(panel);
    panel.addEventListener("keydown", (e) => {
      if (e.key === "Escape") this.closePanel();
    });
    let assignments: Assignment[];
    let remembered: { projectId: string; taskId: string } | null;
    try {
      [assignments, remembered] = await Promise.all([
        ask<Assignment[]>({ type: "assignments" }),
        ask<{ projectId: string; taskId: string } | null>({ type: "remembered", source: this.item.source, workspace: this.item.workspace }),
      ]);
    } catch (e) {
      panel.textContent = (e as Error).message;
      return;
    }
    if (assignments.length === 0) {
      panel.textContent = "You aren't on any projects yet. Ask your admin to add you to one.";
      return;
    }
    panel.replaceChildren(this.form(assignments, remembered));
    panel.querySelector<HTMLSelectElement>("select")?.focus();
  }

  private form(assignments: Assignment[], remembered: { projectId: string; taskId: string } | null): HTMLFormElement {
    const form = document.createElement("form");
    const project = select("Project");
    const byClient = new Map<string, Assignment[]>();
    for (const a of assignments) byClient.set(a.client?.name ?? "", [...(byClient.get(a.client?.name ?? "") ?? []), a]);
    for (const [client, list] of byClient) {
      const group = document.createElement("optgroup");
      group.label = client || "No client";
      for (const a of list) group.append(new Option(a.project_code ? `[${a.project_code}] ${a.project_name}` : a.project_name, a.project_id));
      project.select.append(group);
    }
    const task = select("Task");
    const fillTasks = () => {
      const a = assignments.find((x) => x.project_id === project.select.value);
      task.select.replaceChildren(...(a?.tasks ?? []).map((t) => new Option(t.name, t.task_id)));
    };
    const known = remembered && assignments.find((a) => a.project_id === remembered.projectId);
    project.select.value = known ? remembered!.projectId : assignments[0].project_id;
    fillTasks();
    if (known && [...task.select.options].some((o) => o.value === remembered!.taskId)) task.select.value = remembered!.taskId;
    project.select.addEventListener("change", fillTasks);

    const notesLabel = document.createElement("label");
    notesLabel.textContent = "Notes";
    const notes = document.createElement("input");
    notes.value = this.item.title;
    notesLabel.append(notes);

    const actions = document.createElement("div");
    actions.className = "actions";
    const start = document.createElement("button");
    start.type = "submit";
    start.className = "primary";
    start.textContent = "Start timer";
    const cancel = document.createElement("button");
    cancel.type = "button";
    cancel.textContent = "Cancel";
    cancel.addEventListener("click", () => this.closePanel());
    actions.append(cancel, start);

    const error = document.createElement("p");
    error.className = "error";
    form.append(project.label, task.label, notesLabel, error, actions);
    form.addEventListener("submit", (e) => {
      e.preventDefault();
      if (!task.select.value) {
        error.textContent = "Choose a task.";
        return;
      }
      start.disabled = true;
      ask<TimeEntry>({ type: "start", input: { projectId: project.select.value, taskId: task.select.value, notes: notes.value.trim(), item: this.item } }).then(
        (entry) => {
          this.running = entry;
          this.closePanel();
          this.render();
        },
        (err: Error) => {
          start.disabled = false;
          error.textContent = err.message;
        },
      );
    });
    return form;
  }

  private flash(message: string) {
    const p = document.createElement("div");
    p.className = "panel";
    p.textContent = message;
    this.root.append(p);
    setTimeout(() => p.remove(), 4000);
  }
}

function select(text: string) {
  const label = document.createElement("label");
  label.textContent = text;
  const s = document.createElement("select");
  label.append(s);
  return { label, select: s };
}

void main();
