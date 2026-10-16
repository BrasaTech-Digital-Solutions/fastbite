# Production and the sandbox

FastBite runs as two environments with the same version:

- **Production**: where restaurants trade for real. Tickets are legal invoices and VERI*FACTU records go to
  the AEAT.
- **Sandbox**: where restaurants start. Owners learn FastBite, build their menu, tables and staff, and
  practise. Tickets are training tickets (`TEST-` series) and records go only to the AEAT test environment.
  It is part of the product, not a development environment.

When a restaurant is ready, it is promoted: the owner exports a **production bundle** from the sandbox
(menu, settings, theme and business details; never orders, accounts or payment settings) and imports it into its
production location.

## Linked or standalone

Production is the front door: signup, subscription and the owner's account live there. It sets up each new
location in the sandbox too, and the sandbox trusts only production's signed sign-in links. Each environment
knows the other through three settings:

| Setting | On production | On the sandbox |
|---|---|---|
| `FASTBITE_LINK_URL` | the sandbox's address for browsers | production's address for browsers |
| `FASTBITE_LINK_INTERNAL_URL` | how the server reaches the sandbox (defaults to the above) | how the server reaches production |
| `FASTBITE_LINK_SECRET` | the shared secret, at least 32 characters | the same secret |

Without them an environment runs **standalone**, as before the link existed: the sandbox has its own signup,
and production applies onboarding concepts to its own locations. **Production refuses to start standalone**
unless `FASTBITE_STANDALONE=true` allows it on purpose: an IDE run (the `local` profile sets it), tests or a
demo. A misconfigured server therefore never runs production without its sandbox unnoticed.

### New locations appear in the sandbox by themselves

When production creates a location (free signup, an added location or a paid checkout), it lists it in
`sandbox_provisioning` and a background job (`fastbite.link.provision-delay`, 15 seconds) sends it to the
sandbox: `POST /internal/link/locations`, JSON signed with the shared secret (`X-FastBite-Signature`, HMAC-SHA256
of the time and the content; refused if older than five minutes) and naming the version (`X-FastBite-Version`).

- Production never waits for the sandbox and never fails because of it. A location the sandbox could not
  take (down, or on another version during a release, which it answers with 409) is tried again after 30
  seconds, then doubling up to every half hour, until it is there.
- The sandbox creates the same owner and location; doing it again changes nothing, and a location of another
  owner is refused. The owner gets no usable password there: a linked sandbox is entered through production.
  Sandbox locations have no trial or billing of their own (plan `FREE`); access follows production.
- Only a linked production does this; a standalone one keeps everything to itself.

### Owners enter the sandbox from production

A linked sandbox has no passwords for owners. In production's owner console, **Practise in the sandbox**
(`/owner/sandbox?location=…`) opens it in a new tab:

1. Production checks that the location is the owner's and may trade (an ended trial or subscription closes
   the sandbox too), and makes sure the sandbox has the location, setting it up on the spot if needed. A
   location created before the link is set up the first time its owner enters.
2. It redirects the browser to the sandbox's `/link/enter?token=…`: a link signed with the shared secret
   (HMAC-SHA256) naming the owner, the location and the version, good for 60 seconds and only once. The
   sandbox records each link it takes (`sandbox_sign_in`, platform migration V16).
3. The sandbox signs the owner in with a fresh session and opens that location's owner console.

If the sandbox cannot take the owner yet, nobody is signed in: production says the practice space is being
prepared, and the sandbox answers an expired or used link, or one from another version during a release, with
a page to try again.

In a linked sandbox:

- `/login` and every owner page send owners to production, which sends them straight back with a fresh link.
- An owner's password is refused there, even one from before the link; staff still sign in at their
  restaurant (`/<location>/login`) with the accounts the owner created in the sandbox.
- `/signup` goes to production's signup, and signing out goes back to production.

### Set up in the sandbox, run in production

When the environments are linked, each one does its own part:

- **The sandbox is where a restaurant is set up.** The owner chooses a concept there (Fast Food, Café, Fine
  Dining), builds the menu, tables and team, and practises. Once setup has started, its overview shows how to
  take it to production: export the production bundle there, restore it in production.
- **Production gets its menu only by promotion.** A new location's overview invites the owner into the sandbox
  instead of offering concepts, and demo templates cannot be loaded there (the setup form and the advanced
  tools' "Load demo template" are refused with 409). The backup page explains how to bring in the bundle.
- **Locations, subscriptions, billing and custom domains belong to production.** In a linked sandbox the
  console links to production for them, and the actions themselves (`@ProductionOnly`) send the owner there: a
  page to the same page in production, a form to production's owner console. A location therefore never exists
  only in the sandbox, and nobody pays there.

A standalone environment keeps everything in one place, as before: concepts, demo templates, locations and
billing.

Every page of the sandbox carries a striped banner ("SANDBOX: practice environment, nothing here is real"),
a favicon of its own and "[Sandbox]" in the tab title; a linked sandbox's banner links back to production.
Printed tickets and pages hide the banner.

## The same version, always

A bundle written by one version could break a location of another, so:

- Both environments run the **same image** and are deployed together, only on a release.
- The image carries its version (`FASTBITE_VERSION`, a build argument of `docker/Dockerfile-Multi-Stage`;
  outside an image it is the Maven version). Every archive records the version that exported it.
- A location **refuses a production bundle from another version** (HTTP 409, with a message telling the
  owner to export it again), whether it is imported as a bundle or through the owner console's restore:
  bundles are marked as such when exported. That covers the few minutes of a release when one environment
  is updated and the other is not yet. Backups and demo templates are restored whatever version made them.

## Separate in everything else

Each environment has its own database, file storage, Stripe keys and webhooks, `TABLE_QR_SECRET` and
session cookie. Nothing fiscal crosses between them.

Sessions are kept in each environment's database (`PUBLIC.SPRING_SESSION`, Spring Session JDBC), so any
instance can serve any request and a restart signs no one out. The table is named with its schema because
a connection handed back by a restaurant request can still point at that restaurant's schema. Everything in
a session is serialized, so the native image must have each class registered:
`SessionSerializationHintsTest` signs in and fails if one is missing.

Each environment keeps its own sign-in. Locally both run on `localhost` and browsers share cookies across
ports, so the cookies have different names: `FASTBITE_SESSION` in production and `FASTBITE_SANDBOX_SESSION`
in the sandbox (`FASTBITE_SESSION_COOKIE` overrides the name). Behind Firebase Hosting, which forwards only
the cookie named `__session`, both use `__session` and are kept apart by host instead.

Session cookies are host-only by default (no `Domain` attribute): a cookie set by `fastbite.brasatech.es`
is never sent to `sandbox.fastbite.brasatech.es`, nor to other sites of the company domain, and locally
`kebab.localhost` and `localhost` each have their own sign-in. `FASTBITE_COOKIE_DOMAIN` shares the cookie
with a domain and its subdomains. Do not set it to `fastbite.brasatech.es` while the sandbox is
`sandbox.fastbite.brasatech.es` and both use `__session`: production's cookie would then reach the sandbox.
A restaurant's own custom domain always keeps a cookie of its own.

## Both on one machine

`docker-compose.yml` runs the platform as on the server:

```
docker compose up --build
```

| | Address |
|---|---|
| Proxy (Firebase-like) | http://fastbite.localhost:8088, http://sandbox.fastbite.localhost:8088 |
| Production (direct) | http://localhost:8090, restaurants at `http://<location>.localhost:8090` |
| Sandbox (direct) | http://localhost:8091, restaurants at `http://<location>.localhost:8091` |
| Email from both | http://localhost:8027 (Mailpit) |
| PostgreSQL | `localhost:5434`, user and password `fastbite`, databases `fastbite_production` and `fastbite_sandbox` |

- The local proxy (`docker/nginx/nginx.conf`, port 8088) reproduces Firebase Hosting locally:
  it overwrites client `X-Forwarded-*` headers (sending `X-Forwarded-Host $http_host` with the port),
  strips all cookies except `__session`, sets `proxy_read_timeout 60s`, and leaves buffering on.
- Production and sandbox are configured with `FASTBITE_SESSION_COOKIE=__session` and public/link URLs
  pointing at the proxy (`fastbite.localhost:8088` and `sandbox.fastbite.localhost:8088`). Direct ports 8090/8091
  remain published for debugging.
- Ports 8090 and 8091 leave 8080 free for the app run from the IDE. If another program listens on a port,
  `localhost` may reach it over IPv6 instead of the container: change the port in `.env`.
- Both environments now use `__session` and the direct ports share the host `localhost`, so signing in on
  8090 signs you out on 8091. Use the proxy addresses for anything that involves both.
- The image is built once and both environments run it.
- Before each environment starts, the migration job (`--migration-job`) migrates its database, as on the
  server; the application itself only validates the schemas (`MIGRATION_MODE=validate`).
- Optional settings (version, ports, Stripe test keys, the VERI*FACTU certificate) go in a git-ignored `.env`;
  see `docker/local.env.example`. Without a `.env` everything runs with safe defaults.
- Local production never sends to the AEAT's production environment (`VERIFACTU_PRODUCTION_ENABLED=false`).
  The local sandbox sends training records to the AEAT test environment when the certificate is placed in
  `docker/secrets/` (git-ignored; see the README there).
- `docker compose down` stops everything; `docker compose down -v` also deletes both databases and storage.

### Trying a promotion

1. Sign up at http://fastbite.localhost:8088/signup.
2. Open the location's "Practise in the sandbox", which signs you in to the sandbox.
3. Set the location up there.
4. Export the production bundle (owner console → Location settings → Backup → Export Production Bundle).
5. Restore it in production on the same page of http://fastbite.localhost:8088.
6. You stay signed in to both.

### Release Check (`scripts/release-check.sh`)

Before any release or deployment, ensure the stack is running with the current image (`docker compose up -d`), then run the end-to-end release check against the running Docker Compose containers through the proxy:

```bash
bash scripts/release-check.sh
```

The script:
1. Provisions a throwaway tenant on `http://fastbite.localhost:8088`, enters the sandbox, sets up the cafe concept, and records a cash payment (verifying fiscal ticket creation).
2. Creates a staff user with `CASHIER` role, signs in at `/{tenant}/login`, and tests cashier surfaces (`counter`, `tickets`, `day-report`, `api/cash`, `dashboard`).
3. Sweeps guest pages (`menu`, `allergens`, `declaracion-responsable`), legal pages across all supported languages (`es`, `en`, `pt`), and the cart calculation endpoint.
4. Sweeps owner pages across all setting sections (`legal`, `appearance`, `payments`, `backup`, `advanced`) and POST fragments (`image-gallery`).
5. Exercises promotion: exports the production promotion bundle from the sandbox and imports it into production.
6. Verifies static assets and self-hosted fonts (`live-updates.js`, `fonts.css`, `.woff2` files, `qrcode.min.js`).
7. Greps container logs for both `production` and `sandbox` since the run started for `TemplateProcessingException`, `SpelEvaluationException`, `MissingReflectionRegistrationError`, or `ERROR`.
8. Cleans up temporary files and cookies automatically on exit, outputs a single-line summary, and exits non-zero if any check fails or any error is logged.

## Firebase Hosting & Cloud Run

Firebase Hosting provides the public edge (custom domains, SSL certificates, CDN) and forwards traffic
to Cloud Run services in `europe-southwest1`:
- Production target `production` rewrites to Cloud Run service `fastbite` (`https://fastbite.brasatech.es`).
- Sandbox target `sandbox` rewrites to Cloud Run service `fastbite-sandbox` (`https://sandbox.fastbite.brasatech.es`).

### Prerequisites

- **Hosting site IDs are globally unique:** create them with `firebase hosting:sites:create <id>`. If `fastbite` or `fastbite-sandbox` is taken, pick another ID and use it in `target:apply`.
- Connect the custom domains `fastbite.brasatech.es` and `sandbox.fastbite.brasatech.es` in the Firebase Console.
- Hosting calls Cloud Run as a public client, so both Cloud Run services must allow unauthenticated invocations (`roles/run.invoker` granted to `allUsers`).

### Setup and Deployment

1. Configure project and targets in `.firebaserc`. Its `targets` block is keyed by project ID. Replace `"REPLACE-WITH-GCP-PROJECT-ID"` in both places, or run `firebase use --add` and `target:apply`, which write it under the real ID:
   ```bash
   firebase use --add <gcp-project-id>
   firebase target:apply hosting production fastbite
   firebase target:apply hosting sandbox fastbite-sandbox
   ```
2. Deploy hosting configurations (rewrites and public folder):
   ```bash
   firebase deploy --only hosting
   # Or deploy individual environments:
   firebase deploy --only hosting:production
   firebase deploy --only hosting:sandbox
   ```

### Cloud Run Environment Settings

For each Cloud Run service:
- `FASTBITE_SESSION_COOKIE=__session`: Firebase Hosting forwards only cookies named `__session`.
- `FASTBITE_PUBLIC_URL` and `FASTBITE_LINK_URL`: the public HTTPS origins (each service's own URL, and the other's).
- `FASTBITE_COOKIE_DOMAIN`: must **not** be set. Leaving it empty keeps cookies host-only, preventing session collision between `fastbite.brasatech.es` and `sandbox.fastbite.brasatech.es`.

### Caching and Timeouts

- **Caching:**
  - Static files (`/css`, `/js`, `/images`, `/webjars`) are served with `Cache-Control: max-age=600, public`.
  - Dynamic responses (HTML pages, APIs, redirects) carry `Cache-Control: no-cache, no-store, max-age=0, must-revalidate`.
  - Backend 404 responses also carry `no-store`, preventing the Firebase CDN from caching 404s for 10 minutes (which would otherwise cause newly provisioned restaurants or newly uploaded files to stay "not found").
- **Timeouts & SSE Streaming:**
  - Firebase Hosting terminates requests at 60 seconds with a 504 gateway timeout.
  - Live order tracking SSE streams (`/api/orders/live`, `/api/order-status/live`) close cleanly after 50 seconds (`SSE_TIMEOUT = 50s`) with a `retry: 2000` hint, allowing `EventSource` clients to reconnect seamlessly without triggering a 504.
  - The client helper (`/js/live-updates.js`) includes a 10-second watchdog: if stream buffering prevents the initial `connected` event from arriving within 10 seconds, or if repeated reconnections fail, clients fall back to polling every 5 seconds.
