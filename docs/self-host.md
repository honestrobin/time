# Self-hosting Honest Robin: Time

The self-hosted edition is the same code as Honest Robin Cloud, with every feature. The only thing you supply yourself is credentials for paid third-party networks, such as your own Peppol access-point provider.

## Requirements

- Docker (or Podman) with Compose
- About 1 GB of RAM for the app and 1 GB for Postgres at typical agency sizes
- PostgreSQL 16 (included in the compose file, or bring your own)

## Start

```sh
git clone https://github.com/honestrobin/time.git
cd time
cp deploy/.env.example deploy/.env   # optional: edit settings
docker compose -f deploy/docker-compose.yml up -d
```

Open `HONESTROBIN_BASE_URL` (default http://localhost:8080). The first person to sign up becomes the admin of the instance. After that, sign-up closes and people join by invitation. Set `HONESTROBIN_SIGNUP_MODE=open` to let anyone create an account.

## Configuration

All settings are environment variables.

| Variable | Default | Meaning |
|---|---|---|
| `HONESTROBIN_BASE_URL` | `http://localhost:8080` | Public URL, used in emails. Use `https://` in production; it also turns on secure cookies. |
| `HONESTROBIN_SIGNUP_MODE` | `first_user_only` | `first_user_only`, `open` or `invite_only` |
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
4. Open `https://time.example.com`. The first person to sign up becomes the admin; everyone else joins by invitation.

Caddy gets the certificate by itself. Without SMTP settings, emails (sign-in links, invitations) are written to the app's log (`docker compose -f deploy/docker-compose.yml logs app`); add SMTP in `deploy/.env` before inviting people.

## Prebuilt images

You don't have to build the app yourself: images are published at `ghcr.io/honestrobin/time`.

- `:main` is a **development build**: the newest code that passed every test, for x86 (amd64)
  and Arm (arm64) servers. It is not a release. It can break, and it comes with no promises. It's
  what we run on our own test server; don't use it for real work.
- Version tags (`:1.2.3`, and `:latest` for the newest) come with releases. There are none yet,
  and release images are x86 only for now.

To run an image instead of building, add it to `deploy/.env` and start without building:

```sh
echo 'HONESTROBIN_IMAGE=ghcr.io/honestrobin/time:main' >> deploy/.env
docker compose -f deploy/docker-compose.yml up -d --no-build
```

### What's inside

Each image carries two records made by the build: where it came from (the commit and the CI run
that built it, called provenance) and a list of everything inside, with licences where they are
known (a software bill of materials, or SBOM). To read them:

```sh
docker buildx imagetools inspect ghcr.io/honestrobin/time:main --format '{{ json .Provenance }}'
docker buildx imagetools inspect ghcr.io/honestrobin/time:main --format '{{ json .SBOM }}'
```

The image is our code (AGPL-3.0-only, the source is this repository) on top of the
[Eclipse Temurin](https://adoptium.net) Java runtime image: OpenJDK 25 on Ubuntu (26.04 at the
time of writing; the SBOM has the exact versions). Those parts keep their own licences, many of
them in the GPL family. Where to get their source:

- **OpenJDK:** every Temurin release publishes its source next to the binaries, at
  [adoptium/temurin25-binaries](https://github.com/adoptium/temurin25-binaries/releases)
  (`OpenJDK25U-jdk-sources_<version>.tar.gz`). Its licence notices are in the image under
  `/opt/java/openjdk/legal`.
- **Ubuntu packages:** from Ubuntu's archive, for example with `apt-get source <package>` on
  Ubuntu, or at [launchpad.net/ubuntu](https://launchpad.net/ubuntu). The package list:
  `docker run --rm --entrypoint dpkg ghcr.io/honestrobin/time:main -l`.
- **Java libraries** sit inside the app's jar unchanged, as their own jars, with whatever licence
  files they ship with (not all of them include one). CI refuses any whose licence doesn't fit
  the AGPL (`./gradlew :backend:checkLicense`, against `config/allowed-licenses.json`).

Postgres and Caddy are not part of our image: Compose pulls them from their own publishers.

## Reverse proxy and TLS

Put Caddy, Traefik or nginx in front, terminate TLS there, and forward to port 8080. Set `HONESTROBIN_BASE_URL` to the public `https://` address.

The web app and the browser extension keep a stream open to `/api/v1/me/events` for live timer updates. The app asks proxies not to buffer it (`X-Accel-Buffering: no`, which nginx honours) and sends a comment every 25 seconds; if your proxy closes idle connections sooner, raise its read timeout for that path. Serving over HTTP/2 (any TLS proxy does) avoids browsers' limit of six connections per site.

## The browser extension

The Honest Robin extension (Chrome and Firefox) works with your instance: in its sign-in screen, enter your instance's address instead of leaving it empty. It asks for access to that address only, opens your instance to approve a code, and keeps a personal access token, which you can see and revoke under Profile.

## Backups

Back up two things together:

1. The Postgres database: `docker compose exec db pg_dump -U honestrobin honestrobin > honestrobin.sql`
2. The `data` volume. It contains `secrets.key` and uploaded files. Without `secrets.key`, stored integration credentials (Stripe, accounting, e-invoicing) cannot be decrypted and must be re-entered.

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

## Monitoring

- Liveness and readiness: `/actuator/health/liveness`, `/actuator/health/readiness`
- Prometheus metrics: `/actuator/prometheus`, with `Authorization: Bearer <token>` where the token is what you set in `HONESTROBIN_METRICS_TOKEN` (without it, the endpoint stays closed)

## Database role

The app runs every transaction as the unprivileged role `honestrobin_app`, so Postgres row-level security isolates accounts even if the configured database user is a superuser. The first migration creates this role. If your database user may not create roles, ask your DBA to run `CREATE ROLE honestrobin_app NOLOGIN; GRANT honestrobin_app TO <your user>;` before the first start. The startup log reports whether row-level security is effective.
