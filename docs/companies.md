# Companies that touch your data

Last changed: 5 October 2026. Every change is in
[this file's history](https://github.com/honestrobin/time/commits/main/docs/companies.md).

This is every company that touches your data on Honest Robin Cloud, the service we run for you.
Honest Robin Cloud isn't open yet, so this is the list it would open with today. Nothing touches
your data that isn't on it. It keeps the promise "Your data's home is Europe" in the
[Robin's Code](https://honestrobin.com/code).

A test fails when the code can reach an outside address whose company isn't on this page, so a new
provider can't be added quietly
([`CompanyListTest`](../backend/src/test/kotlin/com/honestrobin/time/platform/CompanyListTest.kt)).
The test can't see what we set up outside the code: our servers, the proxy in front of them and
the mail provider. We keep those on this page by hand.

Each company here has subcontractors of its own. Their lists are on their own websites.

| Company | Country | What it does | Used |
|---|---|---|---|
| Hetzner | Germany | Runs our servers, where your data is stored | Always |
| Cloudflare | United States | Passes traffic on to our servers and protects them from attacks | Always |
| PostHog | United States, with our data in Germany | Counts how accounts use the product | Always |
| Have I Been Pwned | Australia | Checks new passwords against known data breaches | When someone sets a password |
| Mail provider | Not chosen yet | Sends our emails | Always |
| Paddle | United Kingdom | Sells our paid plan to you | When you subscribe |
| Stripe | United States | Takes online payments of your invoices | When you connect your Stripe account |
| Storecove | Netherlands | Sends your e-invoices over the Peppol network | When you connect your Storecove account |
| Intuit (QuickBooks Online) | United States | Gets your invoices and payments, for your books | When you connect QuickBooks |
| Xero | New Zealand | Gets your invoices and payments, for your books | When you connect Xero |
| Harvest | Italy | Gives us your data when you import it from Harvest | When you import from Harvest |

Where we couldn't confirm a fact yet, it says "(to be checked)".

## Used for every account

### Hetzner

- **Country:** Germany. Our servers are in its data centre in Nuremberg, Germany.
- **What it does:** runs the servers that Honest Robin Cloud runs on.
- **What it sees:** everything we store about your account.

### Cloudflare

- **Country:** United States.
- **What it does:** it sits between your browser and our servers. It passes your requests on and
  protects the servers from attacks.
- **What it sees:** everything that goes between your browser and our servers. It decrypts the
  connection on the way, so it can read what passes through. It doesn't store it. What it logs
  about each request, such as IP addresses: (to be checked).

### PostHog

- **Country:** United States (PostHog Inc.). The code sends to PostHog's EU region,
  `eu.i.posthog.com`, which keeps the data in Frankfurt, Germany, on Amazon Web Services.
- **What it does:** counts how accounts use the product: which steps they reach, and which pages
  they open.
- **What it sees:**
  - your workspace's random ID, never a person's;
  - the steps a workspace reaches: sign-up, an import started and checked, the first timer, the
    first invoice sent, an invoice paid online, a subscription started. Some steps add one
    detail: where an import came from and whether its totals matched, the currency of an online
    payment, or the billing period and number of seats of a subscription;
  - the pages opened, as a pattern such as `/invoices/$invoiceId`, never the address you see;
  - with each page view, your browser's IP address and its name and version. PostHog is asked not
    to work out a location from the IP address. Whether our PostHog project throws IP addresses
    away is a setting there: (to be checked).

  Never names, email addresses, amounts or anything about your clients. That's all we count;
  any tracking beyond it would be off until you turn it on.

### Have I Been Pwned

- **Country:** Australia. Its privacy policy names Superlative Enterprises Pty Ltd, in
  Queensland. Its password service, `api.pwnedpasswords.com`, answers through Cloudflare (United
  States).
- **What it does:** tells us whether a new password has appeared in a known data breach, so
  Time can refuse it.
- **What it sees:** the first 5 characters of the password's SHA-1 hash, and our server's IP
  address. Never the password, and never its full hash.

### The mail provider: not chosen yet

We haven't chosen the company that will send Honest Robin Cloud's emails. It will see every email
we send: the addresses, the text, and the invoice PDFs attached to invoices you send your clients.
It goes on this page when we choose it.

## When you subscribe

### Paddle

- **Country:** United Kingdom. Its privacy policy names Paddle.com Market Limited, in London, and
  other Paddle companies in Ireland, the United States and Canada. Which one sells to you, and
  where Paddle stores data: (to be checked).
- **What it does:** sells our paid plan to you as the "merchant of record": it takes the payment,
  handles sales tax and VAT, and gives you its invoices. Addresses: `paddle.com`.
- **What it sees:** the email address of the admin who subscribes, your workspace's ID and the
  number of seats. Then whatever you enter in its checkout, such as your name, address and payment
  details. That goes to Paddle, not to us. Paddle's checkout script loads on our billing page when
  you start a subscription.
- On the free plan, Paddle isn't used.

## When you connect your own account

These companies get nothing until an admin connects your own Stripe, Storecove, QuickBooks or
Xero account, or imports from Harvest. Each connection belongs to one workspace,
and any key or token it needs is stored encrypted. Disconnecting, or stopping a Harvest sync,
ends it.

### Stripe

- **Country:** United States. Which Stripe company holds accounts in Europe, and where it
  processes data: (to be checked).
- **What it does:** takes online payments of your invoices, straight into your own Stripe
  account. We take no fee. You connect with "Connect with Stripe" or with your own secret key.
  Addresses: `stripe.com`.
- **What it sees:** when your client presses "Pay now", the invoice number, the amount due, the
  currency, our IDs for the invoice and your workspace, and the first email address the invoice
  was sent to. Your client then pays on Stripe's own page.

### Storecove

- **Country:** Netherlands (Datajust B.V., trading as Storecove). Where it processes data: (to be
  checked).
- **What it does:** sends your e-invoices over the Peppol network, under your own Storecove
  contract and API key. Sending through a contract of ours is off. Addresses: `storecove.com`.
- **What it sees:** your company's name, address and Peppol ID; each e-invoice you send, with
  your client's details, the lines and the amounts; and the receiver's Peppol ID.

### Intuit (QuickBooks Online)

- **Country:** United States. Where QuickBooks Online processes data: (to be checked).
- **What it does:** receives the invoices and payments you send to your own QuickBooks company,
  which you allow when you connect. Addresses: `intuit.com`.
- **What it sees:** for each client, the name, email address and currency. For each invoice, the
  number, dates, currency, lines (description, quantity, price, tax), discount and reference. For
  each payment, the amount and date.

### Xero

- **Country:** New Zealand. Xero's privacy notice names Xero (NZ) Limited for people in the EEA
  and Xero (UK) Limited for the UK. It says data may be processed in other countries, such as
  Australia, New Zealand and the United States.
- **What it does and sees:** the same as QuickBooks, for your own Xero organisation. Addresses:
  `xero.com`.

### Harvest

- **Country:** Italy. Harvest's privacy policy (15 April 2026) names Bending Spoons S.p.A., in
  Milan, which bought Harvest in 2025. Where Harvest processes data: (to be checked).
- **What it does:** it's where an import from Harvest reads your data from. Addresses:
  `harvestapp.com`, `getharvest.com`.
- **What it sees:** your Harvest token and account ID, with each request. Time only reads from
  Harvest and sends it nothing else. When you stop syncing, Time forgets the token. Receipts are
  downloaded from the addresses Harvest gives; which company stores those files: (to be checked).

## Outside the European Union, and why we still use them

- **Cloudflare (United States)** sees all traffic to Honest Robin Cloud. It protects our servers
  from attacks that could take Time offline.
- **PostHog (United States)** keeps our analytics in its EU region, in Germany, and our code
  sends it little: no names, email addresses, amounts or anything about your clients.
- **Have I Been Pwned (Australia)** is a free, public list of passwords from known breaches.
  The 5 characters it gets are the start of a hash that many passwords share, so they don't
  reveal yours.
- **Paddle (United Kingdom)** is in Europe, but outside the EU. As merchant of record it handles
  sales tax and VAT in every country we sell to, so we can sell worldwide without registering for
  tax in each one.
- **Stripe and Intuit (United States) and Xero (New Zealand)** are used only with your own
  account, when you connect it. They're there so you can keep the payment and accounting tools
  you already use. Time doesn't connect to a European service for online payments or accounting
  yet.

## How far we are

We're heading for no American company in the path of your data. Today two are there for every
account: Cloudflare, which all traffic passes through, and PostHog, which keeps the analytics
above in Germany on Amazon's servers. Stripe and Intuit are there only if you connect them. The
mail provider isn't chosen yet.

## The browser extension

The extension adds a "Track time" button to GitHub, Jira, Asana, Linear and Trello. It reads the
title and address of the item you're looking at and sends them to Time, and nowhere else. The
panel it opens there shows your clients and projects; it's built so that the site's own scripts
can't read it. Those companies get nothing from Time.

## On your own server

The self-hosted edition sends us nothing. It calls an outside service only in these cases, and
you can turn each one off:

| Service | When it's called | To turn it off |
|---|---|---|
| Have I Been Pwned (`api.pwnedpasswords.com`) | When someone sets a password. Only the first 5 characters of its SHA-1 hash leave your server. | Set `HONESTROBIN_PASSWORDS_BREACH_CHECK=false`. Passwords are then checked against the built-in list of common passwords only. |
| Stripe, Storecove, QuickBooks Online, Xero | When an admin connects their own account with one of them. QuickBooks and Xero also need an app you register with them. | Don't connect them, or disconnect them in Settings. |
| Harvest | When an admin imports from Harvest. | Don't import, or stop syncing. |
| Your mail server | When `HONESTROBIN_SMTP_HOST` is set. | Leave it unset. Nothing is emailed then ([self-host.md](self-host.md#configuration) says what happens instead). |
| Your tracing collector | When `HONESTROBIN_TRACING_SAMPLING` is above 0 and `MANAGEMENT_OTLP_TRACING_ENDPOINT` is set. | It's off by default. |

PostHog and Paddle are never called: they're only in the cloud edition, and the self-hosted
edition is the default. Even in the cloud edition, nothing goes to PostHog without a PostHog key.
Where your server runs, and whether a proxy such as Cloudflare sits in front of it, is your
choice ([self-host.md](self-host.md#behind-cloudflare)).
