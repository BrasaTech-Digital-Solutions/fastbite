# Production and the sandbox

FastBite runs as two environments with the same version:

- **Production**: where restaurants trade for real. Tickets are legal invoices and VERI*FACTU records go to
  the AEAT.
- **Sandbox**: where restaurants start. Owners learn FastBite, build their menu, tables and staff, and
  practise. Tickets are training tickets (`TEST-` series) and records go only to the AEAT test environment.
  It is part of the product, not a development environment.

When a restaurant is ready, it is promoted: the owner exports a **production bundle** from the sandbox
(menu, settings and business details; never orders, accounts or payment settings) and imports it into its
production location.

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

Browsers share cookies across ports and subdomains, so the session cookies have different names:
`FASTBITE_SESSION` in production and `FASTBITE_SANDBOX_SESSION` in the sandbox (override with
`FASTBITE_SESSION_COOKIE`). Signing in to one never signs anyone out of the other.

The cookie is shared by the platform's restaurant subdomains, so an owner signs in once. On a server, set
`FASTBITE_COOKIE_DOMAIN` to the platform's own domain (for example `fastbite.brasatech.es`): the cookie is
then shared with that domain and its subdomains only, not with other sites of the company domain such as
`soberana.brasatech.es`, and a restaurant's own domain keeps a cookie of its own. Without it, the last two
parts of the host name are used, which suits `kebab.localhost`.

## Both on one machine

`docker-compose.yml` runs the platform as on the server:

```
docker compose up --build
```

| | Address |
|---|---|
| Production | http://localhost:8090, restaurants at `http://<location>.localhost:8090` |
| Sandbox | http://localhost:8091, restaurants at `http://<location>.localhost:8091` |
| Email from both | http://localhost:8027 (Mailpit) |
| PostgreSQL | `localhost:5434`, user and password `fastbite`, databases `fastbite_production` and `fastbite_sandbox` |

- Ports 8090 and 8091 leave 8080 free for the app run from the IDE. If another program listens on a port,
  `localhost` may reach it over IPv6 instead of the container: change the port in `.env`.
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

1. Sign up in the sandbox (port 8091), create a location and set it up, or load a demo template.
2. Export its production bundle (owner console → Location settings → Backup → Export Production Bundle).
3. Sign up in production (port 8090) and create the production location.
4. Restore the bundle there (same page: check the file, then Review and restore). A bundle from another
   version is refused with the reason.
