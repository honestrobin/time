# Honest Robin: Time — browser extension

Chrome and Firefox (Manifest V3). A timer in the toolbar, and a **Track time** button on Jira,
Asana, GitHub, Linear and Trello that starts a timer with the item's title as notes and links the
entry back to the item (`external_reference`: source, id, workspace, url, title).

## Build

```sh
pnpm install
pnpm --filter @honestrobin/extension build      # dist/chrome and dist/firefox
pnpm --filter @honestrobin/extension build:e2e  # also allows http://localhost, for tests
pnpm --filter @honestrobin/extension test       # recognising items on the fixture pages
```

Load it unpacked: Chrome → `chrome://extensions` → Developer mode → Load unpacked → `dist/chrome`.
Firefox → `about:debugging` → This Firefox → Load Temporary Add-on → `dist/firefox/manifest.json`.

## How it works

- **Signing in** uses the device flow (RFC 8628): the extension asks the instance for a code,
  opens the instance's `/device` page, and the person types the code there and approves it,
  signed in as usual (with two-factor sign-in if they use it). The link never carries the code,
  so nobody can be sent a link that approves someone else's device in one click. The extension then collects a personal access
  token for the account they chose. It works with Honest Robin Cloud and any self-hosted address;
  for a self-hosted one the extension asks for access to that address only. Pasting a token
  (Profile → Personal access tokens) works too.
- **The background** (a service worker in Chrome, an event page in Firefox) makes every API call:
  content scripts can't, as their requests count as the page's and CORS would stop them. It keeps
  the toolbar badge showing how long the timer has run.
- **Live updates**: the open popup listens to `/api/v1/me/events` (server-sent events), so a timer
  started in the web app shows at once, and the web app hears about timers started here.
- **The Track time button** comes from `src/selectors.json`: per site, the URL patterns that make
  a page an item, where the title is, and where the button goes (several fallbacks each). The
  instance serves the same file at `/extension/selectors.json`; once signed in, the extension
  checks it daily and uses it when its `version` is higher (by at most 1000) and it passes the
  checks in `sites.ts` (`newer`): the bundled sites and hosts only, short selectors, links as the
  only attribute read, and regexes that can't take seconds to run. Signing out, or in to another
  instance, drops it. When a site changes its markup, bump `version`, fix the selectors, and
  release the server: extensions pick it up without a new extension release. It is data the
  extension reads, never code it runs.
- **Remembering**: the project and task last used for a workspace (a repository, a Jira site, an
  Asana workspace, a Linear team, a Trello board) are preselected next time.

## Before releasing

- **VERIFY the selectors** against each live site: GitHub (new issues view and classic pull
  requests), Jira Cloud (issue view and boards with `?selectedIssue=`), Asana (task pane), Linear
  (issue view), Trello (card back). The fixtures in `test/fixtures/` mirror what the config expects.
- **Store listings**: Chrome Web Store and Firefox Add-ons accounts, screenshots
  (`e2e/screenshots/m4-*.png` with `SCREENSHOTS=1`), and the permission justifications below.
- The cloud address is `https://time.honestrobin.com` (`src/lib/storage.ts`, `build.mjs`,
  decision record 0017). Changing it after the extension is published makes every user approve
  the new address again.

### Permissions

| Permission | Why |
|---|---|
| `storage` | The sign-in token, the instance address, remembered projects per workspace, the newest selector config. |
| `alarms` | Refresh the toolbar badge every minute (it asks the instance for the running timer) and the selector config daily, both only while signed in. |
| Host `time.honestrobin.com` | Talk to Honest Robin Cloud. |
| Optional hosts (asked for at sign-in) | Talk to a self-hosted instance, only the address the person enters. |
| Content scripts on github.com, *.atlassian.net, app.asana.com, linear.app, trello.com | Show the Track time button. The scripts read the item's title and address (never hidden or password fields). The title and address go to the person's own Honest Robin only when they start a timer; on an item page the script also asks it whether a timer runs for that item, which sends nothing from the page. The button is in a closed shadow root, so the site's own scripts can't read the person's projects through it. |
