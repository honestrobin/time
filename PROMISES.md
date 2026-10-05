# What keeps our promises

Every promise Time makes, from the [Robin's Code](https://honestrobin.com/code) and from our
README, and what keeps each one today. "Tested" means a named test fails if the promise breaks by
accident. Nothing in code can stop a promise being broken on purpose. That's what the terms of
service are for, and they aren't written yet.

The statuses: **Tested**; **Built, no test** (the code does it, and nothing checks it); **Not
built**; **By hand** (kept by how we work, not by code); **Doesn't apply**.

Last checked: 2026-10-05, against the Robin's Code at robinscode ad557db.

## The deal doesn't change after you move in

| Promise | Status | Guarded by |
|---|---|---|
| Your price stays the same for as long as your subscription runs: the database refuses to raise a subscription's locked price. The lock is on our record. Paddle, which takes the payment, charges the subscription's own price. If Paddle ever reported a higher one, we'd log it and keep your lock, but nothing yet tells you. | Tested | `backend/src/test/kotlin/com/honestrobin/time/billing/BillingTest.kt::the database refuses to raise a subscription's price (AT-6_2)`, `backend/src/test/kotlin/com/honestrobin/time/billing/BillingTest.kt::free for one person, a Team subscription for more, seats kept in step and the price never raised` |
| Features you have don't move to a more expensive plan. There's one paid plan, Team, and it differs from the free plan only in how many people can sign in. | Tested | `backend/src/test/kotlin/com/honestrobin/time/platform/PromiseGuardsTest.kt::only the number of seats depends on the plan` |
| Each version of the API keeps working for at least 12 months after the next one comes out. Every endpoint is under `/api/v1`, and there's no v2 yet. | By hand | |
| A feature you use is never removed without 90 days' notice. | By hand | |
| Changes to how you work are in a public, dated changelog, [`CHANGELOG.md`](CHANGELOG.md), before they ship: every change people will notice adds its line in the same pull request. | By hand | |

## No surprise bills

| Promise | Status | Guarded by |
|---|---|---|
| Invoices to your clients: the total in the app, on the PDF and charged online comes from the same arithmetic, checked against the same worked examples, and we take no fee from your client's payment. | Tested | `frontend/src/lib/invoiceMath.test.ts::matches the server's worked example for two named taxes and a discount`, `backend/src/test/kotlin/com/honestrobin/time/invoicing/InvoicingTest.kt::two named taxes and a discount compute like the worked example (AT-3_3)`, `backend/src/test/kotlin/com/honestrobin/time/payments/OnlinePaymentsTest.kt::with no fee taken (AT-3_4)` |
| Your Time subscription: you see the exact amount before you're charged. Not yet in full. Before a new person is added on the Team plan, you see their price for each billing period and that, on the day they start, Paddle charges for what's left of the period, at most that price; not the exact amount. Paddle's checkout shows its amount before the first payment. The billing page shows our list price, while Paddle charges its own price setup. | Not built | |
| Nobody pays for an invitation until it's accepted: checkout, the seat changes sent to Paddle and the billing page count only the people who can sign in, and Paddle hears about a new person only once their access is saved, so a change that rolls back charges nothing. If Paddle can't be reached at that moment, the seat sync sends it within 15 minutes, once. | Tested | `backend/src/test/kotlin/com/honestrobin/time/billing/BillingTest.kt::nobody is billed for an invitation until it's accepted`, `backend/src/test/kotlin/com/honestrobin/time/billing/BillingTest.kt::billing starts when the invitation is accepted`, `backend/src/test/kotlin/com/honestrobin/time/billing/BillingTest.kt::an accepted invitation that rolls back charges nothing`, `backend/src/test/kotlin/com/honestrobin/time/billing/BillingTest.kt::an invitation accepted after the subscription ended charges nothing`, `backend/src/test/kotlin/com/honestrobin/time/billing/BillingTest.kt::when Paddle is down at an accept, the person still gets in and the seat sync bills the seat once` |
| The price list is public and complete. The price isn't decided yet; the website says so, and it will be there before anyone pays. | Not built | |
| Nothing is metered for billing: Honest Robin Cloud limits seats only. Where anyone can sign up, mail also has daily limits against abuse (50 invitations and 300 invoice recipients a day). They're never billed. | Built, no test | |
| Sending e-invoices over Peppol uses your own Storecove account, so there's nothing for us to pass on. | Doesn't apply | |

## You get the whole product

| Promise | Status | Guarded by |
|---|---|---|
| The self-hosted edition is the same software with every feature: both editions offer the same endpoints, apart from paying Honest Robin Cloud, and only the billing and analytics code may treat them differently. | Tested | `backend/src/test/kotlin/com/honestrobin/time/platform/EditionParityTest.kt::editions expose the same endpoints except cloud billing`, `backend/src/test/kotlin/com/honestrobin/time/platform/EditionParityTest.kt::only allowlisted packages refer to the edition` |
| Two-factor sign-in, the audit log, export and the API come with every plan, including the free one. | Tested | `backend/src/test/kotlin/com/honestrobin/time/platform/PromiseGuardsTest.kt::the free plan has two-factor sign-in, the audit log, export and the API` |

## You can always leave

| Promise | Status | Guarded by |
|---|---|---|
| Move out: one button in Settings → Account, on every plan and in every state of an account, that changes nothing in it and asks only for a recent sign-in. It gives one zip of everything in open formats, with every invoice as a PDF (Factur-X inside where the account sends those) and as XRechnung and Peppol BIS where the invoice has the data; it says where you can go next; and it lists what's still connected (tokens, devices, open invitations, Stripe, QuickBooks, Xero, Peppol, a Harvest sync, invoice links and reminders, and on Honest Robin Cloud the subscription), with how to end each. An admin can end each in every state of the account, read-only included, except invoice links, which end only when the account is deleted. Not yet: invoices aren't kept as they were sent, so the zip's files are drawn from today's data and differ from what the client got where the invoice or the account changed since; and the zip's list leaves out the subscription, which the Move out page shows. | Tested | `backend/src/test/kotlin/com/honestrobin/time/export/MoveOutTest.kt::move out works in every state of an account and changes nothing`, `backend/src/test/kotlin/com/honestrobin/time/export/MoveOutTest.kt::move out lists everything still connected to the account, and where to end each`, `backend/src/test/kotlin/com/honestrobin/time/export/MoveOutTest.kt::move out gives every invoice as a file to open, with its e-invoice where it has one`, `frontend/src/features/settings/MoveOut.test.tsx::the Move out button is in Settings → Account, in every state of an account`, `frontend/src/features/settings/MoveOut.test.tsx::the Move out page lists what's still connected, how to end each, and that moving out changes nothing`, `backend/src/test/kotlin/com/honestrobin/time/export/EndConnectionsTest.kt::a read-only account can still end every connection` |
| Cancel in the app, in two clicks: "Cancel the Team plan" on the billing page, then a dialog that says what happens and "Cancel at the end of this period". Nothing changes until the end of the period that's paid for; Paddle hears it once, and only to cancel then. Until that day, "Keep the Team plan" takes it back. Only admins can cancel. The tests check the request and the dialog; Paddle's real API is checked against a stand-in. | Tested | `backend/src/test/kotlin/com/honestrobin/time/billing/BillingTest.kt::an admin cancels in the app in two clicks, and nothing changes until the period ends`, `backend/src/test/kotlin/com/honestrobin/time/billing/BillingTest.kt::cancelling reaches Paddle once, at the end of the period`, `backend/src/test/kotlin/com/honestrobin/time/billing/BillingTest.kt::only an admin can cancel the Team plan`, `backend/src/test/kotlin/com/honestrobin/time/billing/BillingTest.kt::the Team plan can be kept until the day it ends`, `frontend/src/features/settings/CancelPlan.test.tsx::cancels in two clicks, at the end of the period, after saying plainly what happens` |
| Pay monthly or yearly, your choice, and no plan is yearly-only. Monthly is always offered once billing is on; yearly is optional. | Built, no test | |
| A full export works on every plan and in every state of an account: free, Team, lapsed, waiting to be deleted, and unpaid or cancelled on Honest Robin Cloud. | Tested | `backend/src/test/kotlin/com/honestrobin/time/export/MoveOutTest.kt::move out works in every state of an account and changes nothing`, `backend/src/test/kotlin/com/honestrobin/time/export/AccountDataTest.kt::a lapsed account is read-only but can still export its data (AT-6_3)`, `backend/src/test/kotlin/com/honestrobin/time/billing/BillingTest.kt::a lapsed account can deactivate people and is active again within the free plan`, `backend/src/test/kotlin/com/honestrobin/time/export/AccountDataTest.kt::an account waiting to be deleted can still export its data`, `backend/src/test/kotlin/com/honestrobin/time/platform/PromiseGuardsTest.kt::the free plan has two-factor sign-in, the audit log, export and the API` |
| Nothing we store is left out of the export: a test fails when a new table is neither exported nor left out on purpose. | Tested | `backend/src/test/kotlin/com/honestrobin/time/export/AccountDataTest.kt::every table is either exported or left out on purpose` |
| An export imports into a fresh account, identical table by table. It's tested within one edition, not yet from the cloud into a self-hosted instance. | Tested | `backend/src/test/kotlin/com/honestrobin/time/export/AccountDataTest.kt::an export imports back after the account is deleted, identical table by table (AT-6_1)` |
| The code is open source with no contributor licence agreement: the AGPL for the app, the MIT licence for the API client. CI checks every file's licence header. | Tested | `scripts/check-license-headers.sh`, `LICENSE`, `packages/api-client/LICENSE` |

## Your data works for you

| Promise | Status | Guarded by |
|---|---|---|
| Nobody sees another account's data: the database filters every query by account, and refuses a reference into another account. | Tested | `backend/src/test/kotlin/com/honestrobin/time/platform/RowLevelSecurityTest.kt::unfiltered queries only see the current tenant`, `backend/src/test/kotlin/com/honestrobin/time/platform/SecurityRegressionTest.kt::the database itself refuses a reference into another account` |
| Page views are counted in the cloud edition only, under the account and never the person, without cookies, and not at all when your browser sends Do Not Track or Global Privacy Control. The app keeps the account's id in your browser's local storage and uses it for this. Your IP address reaches PostHog, which counts the views on servers in the EU. | Tested | `frontend/src/lib/analytics.test.ts::sends the route pattern under the account's id, without cookies`, `frontend/src/lib/analytics.test.ts::respects Global Privacy Control and Do Not Track` |
| Product analytics count the steps an account reaches, such as its first invoice, without names or email addresses. | Tested | `backend/src/test/kotlin/com/honestrobin/time/analytics/FunnelEventsTest.kt::the funnel from signup to an invoice paid online` |
| A self-hosted instance sends us nothing. It does check new passwords against Have I Been Pwned, which its owner can turn off. | Tested | `backend/src/test/kotlin/com/honestrobin/time/analytics/FunnelEventsTest.kt::the web app sends page views in the cloud edition only, and only to PostHog` |
| You can have your account deleted: 14 days to change your mind, then its data is gone, and our access to your Stripe account, your Paddle subscription and your books ends. One person's data can't yet be deleted on its own: people can be deactivated. | Tested | `backend/src/test/kotlin/com/honestrobin/time/export/AccountDataTest.kt::an export imports back after the account is deleted`, `backend/src/test/kotlin/com/honestrobin/time/billing/BillingTest.kt::deleting an account cancels its subscription at Paddle`, `backend/src/test/kotlin/com/honestrobin/time/payments/OnlinePaymentsTest.kt::deleting an account ends Honest Robin's access at Stripe`, `backend/src/test/kotlin/com/honestrobin/time/accounting/AccountingSyncTest.kt::deleting an account ends Honest Robin's access to its books` |
| No ads, your data is never sold, and it never trains AI. Time has no AI features. | By hand | |
| Every number about your account is yours, in the app, the API or the export. Ask for one that's missing, and we build it in for everyone. | By hand | |

## We tell you the truth

| Promise | Status | Guarded by |
|---|---|---|
| A status page shows real uptime, and every outage gets a public write-up. | Not built | |
| Our plans are public: [`ROADMAP.md`](ROADMAP.md), and every decision someone might question in [`docs/decisions/`](docs/decisions/). | By hand | |

## Your customers get the same deal

| Promise | Status | Guarded by |
|---|---|---|
| Your clients get a link to each invoice, with its PDF and a way to pay online, and lines can be grouped by project, task, person or entry. There's no page where a client sees all their invoices. | Tested | `backend/src/test/kotlin/com/honestrobin/time/invoicing/InvoicingTest.kt::sending emails the client with the PDF and a link, and the link opens the invoice for anyone who has it` |

## If we're sold or shut down

| Promise | Status | Guarded by |
|---|---|---|
| Notice before a cloud service stops, exports afterwards, unused paid time refunded, and a buyer bound by these promises. These go into the terms of service, which aren't written yet. | Not built | |

## It's a joy to use

| Promise | Status | Guarded by |
|---|---|---|
| No mail meant to bring you back. Timesheet reminders, invoice reminders and budget alerts stay off until someone turns them on. Some mail can't be turned off: a rejected timesheet, security notices, a finished export, and account deletion. | Built, no test | |
| A person is one step away from every page: Help, at the foot of the menu or behind `?`, first shows the address where a person reads every message and answers it. | Tested | `frontend/src/features/shell/AppShell.test.tsx::a person is one step away from every page, in both editions`, `frontend/src/features/shell/AppShell.test.tsx::the ? key opens help too`, `e2e/tests/10-m1-tour.spec.ts::M1 pages render with data and without raw translation keys` |
| No AI without a reason: Time has no AI features. | Doesn't apply | |

## We trust you too

| Promise | Status | Guarded by |
|---|---|---|
| Adding a person beyond your plan asks first and shows the price. On the free plan, an invitation, also one sent again, is refused with the Team prices, and the Team page has one button that opens the checkout; after that, the invitation is sent again. On the Team plan, an invitation, sending it again, an invitation link and bringing someone back are refused with the price of one more person until an admin says yes to that same price. Invitations already waiting when Team starts are agreed to at checkout, where they're shown with their price, not one by one. A Harvest sync never brings anyone back. | Tested | `backend/src/test/kotlin/com/honestrobin/time/billing/BillingTest.kt::the free plan's refusal shows the Team price`, `backend/src/test/kotlin/com/honestrobin/time/billing/BillingTest.kt::inviting onto the Team plan is refused, with the price, until an admin confirms`, `backend/src/test/kotlin/com/honestrobin/time/billing/BillingTest.kt::a yes to a price that's no longer right is refused, with the price now`, `backend/src/test/kotlin/com/honestrobin/time/billing/BillingTest.kt::sending an invitation again asks again, on either plan`, `backend/src/test/kotlin/com/honestrobin/time/billing/BillingTest.kt::an invitation link asks first too, and works once the price is confirmed`, `backend/src/test/kotlin/com/honestrobin/time/billing/BillingTest.kt::bringing someone back onto the Team plan asks first, and bills from that moment`, `backend/src/test/kotlin/com/honestrobin/time/billing/BillingTest.kt::bringing back someone whose invitation is still open asks first, and bills once they accept`, `backend/src/test/kotlin/com/honestrobin/time/billing/BillingTest.kt::an invitation waiting when Team starts is billed from its acceptance`, `backend/src/test/kotlin/com/honestrobin/time/importers/HarvestSyncTest.kt::the sync never brings back someone deactivated here`, `frontend/src/features/team/Seats.test.tsx::shows what one more person costs, and says yes only on its own button, which names the price` |
| Nothing you already have stops working because someone joined. When a subscription ends, an invitation accepted afterwards that the free plan has no room for is refused, with a plain message for the person invited, and nothing changes. An account that turned read-only when its subscription ended can deactivate people, and only that, and works again as soon as a deactivation gets it within the free plan (otherwise at the next run of the billing job, about every 15 minutes); a running timer stops where the account turned read-only. The billing page and the read-only notice say how, with the free plan's size. The tests check the numbers the page reads, not the page. | Tested | `backend/src/test/kotlin/com/honestrobin/time/billing/BillingTest.kt::an invitation accepted after the subscription ended is refused and changes nothing`, `backend/src/test/kotlin/com/honestrobin/time/billing/BillingTest.kt::a lapsed account can deactivate people and is active again within the free plan`, `backend/src/test/kotlin/com/honestrobin/time/billing/BillingTest.kt::a lapsed account within a larger free plan is active again at the next billing job` |
| You can see your use against your plan: the billing page shows the people who can sign in, the same count checkout and Paddle use, the seats Paddle bills, and open invitations as not billed. A new person reaches Paddle at once, a deactivation within 15 minutes. The tests check the numbers the page reads, not the page. | Tested | `backend/src/test/kotlin/com/honestrobin/time/billing/BillingTest.kt::nobody is billed for an invitation until it's accepted`, `backend/src/test/kotlin/com/honestrobin/time/billing/BillingTest.kt::when Paddle is down at an accept, the person still gets in and the seat sync bills the seat once` |

## Your data's home is Europe

| Promise | Status | Guarded by |
|---|---|---|
| Your data is stored in the EU: Honest Robin Cloud runs on Hetzner's servers in Nuremberg, Germany. On its way there it passes through Cloudflare, an American company, which doesn't store it. | By hand | |
| Every company that touches your data is listed in [`docs/companies.md`](docs/companies.md), with its country and what it does, and a test fails when the code can reach a host that isn't on it. | Tested | `backend/src/test/kotlin/com/honestrobin/time/platform/CompanyListTest.kt::every outside host the code can reach is on the public list of companies`, `backend/src/test/kotlin/com/honestrobin/time/platform/CompanyListTest.kt::every host kept off the list is still in the code` |

## You can see how it's made

| Promise | Status | Guarded by |
|---|---|---|
| Every change says who or what wrote it: each commit names the model that wrote it, and a daily check looks at every new one. | By hand | |
| The rules our agents work by are public. They will be once sign-up opens; today only Time's own are. | Not built | |
| Before every release, a named person says what they checked and what they didn't. There hasn't been a release yet. | Not built | |

## Where our words are ahead of the code

Places where something we've published says more than Time does today. Each one has to change:
the words, or the code.

- The Robin's Code promises the documents you must keep, such as your invoices, as files you can
  open. Time doesn't keep an invoice as it was sent: the export draws each one from today's data,
  so an invoice edited or paid since, or sent before the company address changed, differs from
  what the client received.
- The Robin's Code promises you see the exact amount before you're charged. Before a new person
  is added on the Team plan, Time shows their price for each billing period and the most Paddle
  charges on the day they start, not the exact prorated amount.
