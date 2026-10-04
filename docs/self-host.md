# Self-hosting Honest Robin: Time

The self-hosted edition is the same code as Honest Robin Cloud, with every feature. The only thing you supply yourself is credentials for paid third-party networks, such as your own Peppol access-point provider.

## Requirements

- Docker (or Podman) with Compose
- About 1 GB of RAM for the app and 1 GB for Postgres at typical agency sizes
- PostgreSQL 18 (included in the compose file, or bring your own)

## Start

```sh
git clone https://github.com/honestrobin/time.git
cd time
cp deploy/.env.example deploy/.env   # optional: edit settings
docker compose -f deploy/docker-compose.yml up -d
```

Open `HONESTROBIN_BASE_URL` (default http://localhost:8080). The first person to sign up becomes the admin of the instance. They need the setup code the app writes to its log until someone has signed up (`docker compose -f deploy/docker-compose.yml logs app | grep "setup code"`), so nobody who finds a new instance before you can claim it. After that, sign-up closes and people join by invitation. Set `HONESTROBIN_SIGNUP_MODE=open` to let anyone create an account.

## Configuration

All settings are environment variables.

| Variable | Default | Meaning |
|---|---|---|
| `HONESTROBIN_BASE_URL` | `http://localhost:8080` | Public URL, used in emails. Use `https://` in production; it also turns on secure cookies. |
| `HONESTROBIN_SIGNUP_MODE` | `first_user_only` | `first_user_only`, `open` or `invite_only` |
| `HONESTROBIN_SETUP_CODE` | made at startup | The code the first sign-up needs. Unset, the app makes one each time it starts and writes it to its log. Set it when several app servers run, so they agree. |
| `HONESTROBIN_DB_URL` / `_USER` / `_PASSWORD` | local `honestrobin` database | JDBC connection to Postgres |
| `HONESTROBIN_SMTP_HOST` / `_PORT` / `_USER` / `_PASSWORD` | unset | Outgoing mail. Without SMTP, emails (including sign-in links) go to the application log. |
| `HONESTROBIN_MAIL_FROM` | `Honest Robin <no-reply@localhost>` | Sender address |
| `HONESTROBIN_SECRETS_KEY_FILE` | `/data/secrets.key` | Key that encrypts stored integration credentials. Generated on first start. |
| `HONESTROBIN_SECRETS_KEY` | unset | The same key as base64, if you prefer an environment variable to a file |
| `HONESTROBIN_STORAGE_PATH` | `/data/files` | Receipts, invoice PDFs and exports (local driver) |
| `HONESTROBIN_LOG_FORMAT` | plain | `ecs` or `logstash` for structured JSON logs |
| `HONESTROBIN_TRACING_SAMPLING` | `0.0` | OpenTelemetry trace sampling (0–1). Set `MANAGEMENT_OTLP_TRACING_ENDPOINT` as well. |
| `HONESTROBIN_FORWARD_HEADERS` | `native` | Trust `X-Forwarded-*` from your reverse proxy |
| `HONESTROBIN_MEMORY` | `1536m` | Memory cap for the app container; Java uses 75% of it. 1.5 GB suits a team; raise it for large accounts |
| `HONESTROBIN_PASSWORDS_BREACH_CHECK` | `true` | Check new passwords against Have I Been Pwned (see below) |
| `HONESTROBIN_SESSION_TTL` / `_MAX_LIFETIME` | `30d` / `90d` | Sign-ins end after 30 days unused, and 90 days after signing in at the latest |

The self-hosted edition sends **no telemetry**. The one outbound request it makes on its own is
the breached-password check: when someone chooses a password, the first five characters of its
SHA-1 hash go to `api.pwnedpasswords.com` (k-anonymity: neither the password nor its full hash
leaves the server), and the answer says whether it appeared in a known breach. Set
`HONESTROBIN_PASSWORDS_BREACH_CHECK=false` to turn it off, for example on a server without
internet access; passwords are then checked against the built-in list of common passwords only.
If the service can't be reached, the password is accepted.

## On a server with your own domain

1. Get a small Linux server with Docker (2 GB of memory is plenty for a team) and point your domain at it: an `A` record (and `AAAA` for IPv6) for, say, `time.example.com`.
2. On the server: `git clone https://github.com/honestrobin/time && cd time`
3. `HONESTROBIN_DOMAIN=time.example.com docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.https.yml up -d --build`
4. Open `https://time.example.com`. The first person to sign up becomes the admin, with the setup code from the app's log; everyone else joins by invitation.

Caddy gets the certificate by itself. Without SMTP settings, emails (sign-in links, invitations) are written to the app's log (`docker compose -f deploy/docker-compose.yml logs app`); add SMTP in `deploy/.env` before inviting people.

## Prebuilt images

CI builds an image of every change to `main` that passes all tests, for x86 (amd64) and Arm
(arm64) servers, at `ghcr.io/honestrobin/time`. For now these images are private: they run our
own test server. Public images will come with the first release. Until then, build from source
as above; it's the same code.

## Reverse proxy and TLS

Put Caddy, Traefik or nginx in front, terminate TLS there, and forward to port 8080. Set `HONESTROBIN_BASE_URL` to the public `https://` address.

The web app and the browser extension keep a stream open to `/api/v1/me/events` for live timer updates. The app asks proxies not to buffer it (`X-Accel-Buffering: no`, which nginx honours) and sends a comment every 25 seconds; if your proxy closes idle connections sooner, raise its read timeout for that path. Serving over HTTP/2 (any TLS proxy does) avoids browsers' limit of six connections per site.

### Behind Cloudflare

To use Cloudflare's proxy (the orange cloud) in front of the HTTPS setup above:

1. Create the record as **DNS only** first, start the app, and wait until
   `https://<your domain>` works: Caddy has its certificate. Then turn the proxy on.
2. In Cloudflare, set SSL/TLS to **Full (strict)**. "Always Use HTTPS" can be on: Cloudflare
   still passes Let's Encrypt's checks (`/.well-known/acme-challenge/`) through to Caddy on port
   80, which is how Caddy renews its certificate behind the proxy.
3. Only an `A` record is needed; Cloudflare serves IPv6 visitors itself. With an `AAAA` record,
   Cloudflare may reach your server over IPv6, which Docker passes on from its own address, and
   the visitor's address is lost.
4. Tell Caddy to take Cloudflare's word for visitors' addresses, in `deploy/.env`:

   ```sh
   HONESTROBIN_TRUSTED_PROXIES=<the ranges at https://www.cloudflare.com/ips/, separated by spaces>
   HONESTROBIN_CLIENT_IP_HEADER=CF-Connecting-IP
   ```

   Without this, everyone seems to come from Cloudflare, and sign-in protection and the audit log
   see Cloudflare's addresses instead of people's. Requests that don't come from those ranges
   can't set the header. Cloudflare rarely changes its ranges, but check now and then.
5. Optionally, let ports 80 and 443 in only from Cloudflare's ranges in your firewall, so no one
   can go around it.

Cloudflare decrypts the traffic it proxies, so it can read it. If you run Honest Robin for other
people, say so in your privacy notice.

## The browser extension

The Honest Robin extension (Chrome and Firefox) works with your instance: in its sign-in screen, enter your instance's address instead of leaving it empty. It asks for access to that address only, opens your instance to approve a code, and keeps a personal access token, which you can see and revoke under Profile.

## Backups

Back up two things together:

1. The Postgres database: `docker compose exec db pg_dump -U honestrobin honestrobin > honestrobin.sql`
2. The `data` volume. It contains `secrets.key` and uploaded files. Without `secrets.key`, stored integration credentials (Stripe, accounting, e-invoicing) cannot be decrypted and must be re-entered.

To restore, load the database into an empty database before the app first starts on it, and
create the app's role first: `CREATE ROLE honestrobin_app NOLOGIN;`. A dump holds the rights given
to that role, but not the role itself (roles live outside any one database), so without it those
rights are skipped and the restore still finishes. The app then starts but can't switch to the
role, and row-level security stops keeping accounts apart; only a warning in the log says so.
Then put back the `data` volume, start the app, and check that its startup log says row-level
security is active.

Test a restore now and then.

Backups keep deleted accounts until they rotate out: when an account is deleted for good (14 days after an admin asks), it stays in older backups for as long as you keep them. Pick a retention that matches what you promise your users.

## E-invoicing and accounting

- **E-invoices** (Factur-X/ZUGFeRD, XRechnung, Peppol BIS) need nothing extra: they are made on your server.
- **Sending over Peppol** goes through an access point. Each account can connect its own [Storecove](https://www.storecove.com) contract with an API key, under Settings → Invoice settings. To let all accounts send through one contract of yours, set `HONESTROBIN_STORECOVE_PLATFORM_API_KEY` and `HONESTROBIN_STORECOVE_PLATFORM_WEBHOOK_SECRET`, and point a Storecove webhook at `https://<your server>/webhooks/storecove` with that secret in an `X-Webhook-Secret` header.
- **QuickBooks Online and Xero** need an app registered with Intuit and Xero, so that your instance can ask people for access. Register one with each, with the redirect address `https://<your server>/api/v1/public/accounting/qbo/callback` (or `.../xero/callback`), and set `HONESTROBIN_QBO_CLIENT_ID` and `HONESTROBIN_QBO_CLIENT_SECRET` (and `HONESTROBIN_XERO_CLIENT_ID`, `HONESTROBIN_XERO_CLIENT_SECRET`). For QuickBooks sandbox companies, also set `HONESTROBIN_QBO_API_BASE_URL=https://sandbox-quickbooks.api.intuit.com`.

## Moving between instances

Any admin can download a full export of an account (Settings → Account → Export all data) and import it into another instance, self-hosted or Honest Robin Cloud, under Settings → Account → Import an export. The format is described in [export-format.md](export-format.md). Imports larger than 2 GB are refused unless you raise `HONESTROBIN_ACCOUNT_DATA_IMPORT_MAX_BYTES`. Finished exports can be downloaded for 7 days (`HONESTROBIN_ACCOUNT_DATA_EXPORT_RETENTION`).

## Upgrades

Pull the new version and restart: `git pull && docker compose -f deploy/docker-compose.yml up -d --build`. Database migrations run automatically on start. Read the release notes before a major version.

### From PostgreSQL 16 to 18

Instances set up before 4 October 2026 ran PostgreSQL 16. The app now needs 18 (its database
library supports no older version), and the compose file keeps PostgreSQL 18's data in a new
volume. Move the data across once, in this order, before the app starts on the new database:
an empty database looks like a fresh instance. (Since 4 October 2026 nobody can claim one without
the setup code from the log, but your data still wouldn't be there.)

```sh
C="docker compose -f deploy/docker-compose.yml"   # add -f deploy/docker-compose.https.yml if you use it
$C stop app
$C exec -T db pg_dumpall -U honestrobin > honestrobin-pg16.sql   # everything, the app's role included
git pull
$C up -d db                                                        # PostgreSQL 18, on the new volume
$C exec -T db psql -U honestrobin -d postgres -q < honestrobin-pg16.sql
$C up -d --build
$C logs app | grep "Row-level security"                            # should say it's active
```

The restore reports that the role `honestrobin` and the database `honestrobin` already exist;
that's expected. Sign in and check your data, then remove the old volume:
`docker volume rm honestrobin-time_db`.

## Monitoring

- Liveness and readiness: `/actuator/health/liveness`, `/actuator/health/readiness`
- Prometheus metrics: `/actuator/prometheus`, with `Authorization: Bearer <token>` where the token is what you set in `HONESTROBIN_METRICS_TOKEN` (without it, the endpoint stays closed)

## Database role

The app runs every transaction as the unprivileged role `honestrobin_app`, so Postgres row-level security isolates accounts even if the configured database user is a superuser. The first migration creates this role. If your database user may not create roles, ask your DBA to run `CREATE ROLE honestrobin_app NOLOGIN; GRANT honestrobin_app TO <your user>;` before the first start. The startup log reports whether row-level security is effective.
