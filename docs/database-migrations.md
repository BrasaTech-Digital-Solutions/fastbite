# Versioned database upgrades

Flyway 11.14.1 (managed by Spring Boot) owns schema changes. Hibernate and Spring Session no longer create or update tables. Normal application startup validates every restaurant schema and refuses missing, pending, failed, changed or unknown migrations. The `local` and `demo` profiles migrate automatically for development; do not enable them in production.

Migration sources live in `fastbite/adapter-out/jpa/src/main/resources/db/migration`:

- `restaurant/V1__Restaurant_schema.sql` runs in PUBLIC and each `tenant_*` schema.
- `platform/V2__Platform_schema.sql` runs only in PUBLIC for sessions, locations, owner invitations and registration state.
- `platform/V4__Restaurant_subscriptions.sql` adds per-location trials, billing state and volume-quote requests. See [subscription rollout](subscriptions.md) before migrating existing locations.
- `platform/V5__Owner_onboarding.sql` adds persistent per-location setup progress. See [owner workspace](owner-workspace.md).
- `V3__LegacyTranslations` is an explicitly registered Java migration. It copies legacy translation rows into their parent records and removes the old translation tables after copying.

Every schema has its own `flyway_schema_history`. PUBLIC has versions 1, 2, 3, 4 and 5; restaurant schemas have 1 and 3. Allocate globally unique version numbers for future changes. Never edit a released migration or its Java helper; add a new version. Flyway checks SQL checksums, and the Java migration declares its checksum explicitly.

New restaurant registration migrates the new schema before creating its owner. The runtime therefore still needs permission to create schemas and tables for registration. This release separates deployment upgrades from startup, but does not yet separate runtime and provisioning database privileges. Existing `tenant_kebab` schemas are preserved. There is no automatic clean, repair or schema deletion.

## Migration-only process

Build the application, then set `DB_URL`, `DB_USER` and `DB_PASSWORD` for the intended database in the process environment. The job requires an explicit H2 or PostgreSQL JDBC URL; it never falls back to the application's default database. Avoid credentials in shell arguments or checked-in files.

```text
java -jar webapplication/target/webapplication-0.0.1-SNAPSHOT.jar --migration-job
java -jar webapplication/target/webapplication-0.0.1-SNAPSHOT.jar --migration-job --validate-only
```

This process starts no HTTP server, Spring application context, demo seeding, mail or payment integration. Exit code 0 means success, 1 means migration/validation failure, and 2 means invalid configuration or arguments. A successful run can be repeated. Flyway coordinates competing migrators through database locking; the deployment job should nevertheless have one task and no automatic retries.

## First adoption of an existing database

1. Stop writes, including registration, and take a complete database backup or recoverable snapshot. A restaurant ZIP export excludes platform tables and is not sufficient. Test restoration against a separate database first.
2. Confirm the target database and inventory PUBLIC plus every `tenant_*` schema. Rehearse adoption on a restored copy before touching production.
3. Run the same release with `--migration-job --adopt-existing` once. This explicitly baselines nonempty schemas at version **0**, then runs the compatibility DDL and translation migration. Existing tables are retained and missing supported columns are added; arbitrary schema drift is not repaired.
4. Run `--migration-job --validate-only`, check preserved users, paid orders, translations, locations and owner invitations, then start the application with profile `jpa` and the default validation mode.
5. Remove the adoption argument from subsequent deployment jobs. Keep the backup until application verification is complete.

Do not use the usual Flyway baseline version 1 here: that would skip the compatibility migration. [Flyway baseline documentation](https://documentation.red-gate.com/flyway/reference/commands/baseline) explains which migrations are excluded by a baseline.

An untracked nonempty schema is rejected without explicit adoption. If a migration fails, stop rollout and investigate its history and physical schema. A later run may resume after a transactional rollback; an H2 failure can leave committed DDL and a failed history entry that needs deliberate operator recovery. Do not delete history, automatically repair it or change checksums to make validation pass. Upgrades are sequential across schemas, not atomic across the entire database. Restore the full backup if recovery requires reverting the release.

## Cloud Run deployment

`deploy/cloud-run-migrations.yaml` is a template, not an applied deployment. Replace its project, region, repository, release and service-account placeholders. Use the JVM container built from `docker/Dockerfile-NonNative`, pointing its `NAME` build argument at the packaged webapplication JAR. Use the same immutable release image for the migration job and application.

Provide the three referenced Secret Manager secrets and grant the job service account access to them. Configure database network access for your environment. The job uses one task, parallelism one, no automatic retries and a 15-minute timeout. Run and await successful job completion before deploying the service. For first adoption only, add `--adopt-existing` to its arguments after completing the backup and rehearsal steps. Subsequent runs use only `--migration-job`.

Cloud Run references: [job creation](https://docs.cloud.google.com/run/docs/create-jobs) and [v1 YAML specification](https://docs.cloud.google.com/run/docs/reference/yaml/v1).

## Cloud SQL Micro Tier & PostgreSQL Migration Guidelines

When migrating a PostgreSQL database or importing dumps between instances/providers (Neon, Cloud SQL, AWS RDS, local Docker):

1. **Identity & Auto-Increment Sequences**:
   - Ensure all auto-incrementing ID columns (`@GeneratedValue(strategy = GenerationType.IDENTITY)`) preserve `GENERATED BY DEFAULT AS IDENTITY` or `DEFAULT nextval(...)`.
   - After importing data, always synchronize sequence values to the highest existing ID to avoid key collisions on insert:
     ```sql
     SELECT setval(pg_get_serial_sequence(table_name, 'id'), COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) FROM table_name;
     ```

2. **Constraints & Keys Verification**:
   - Verify that all `PRIMARY KEY`, `UNIQUE`, and `FOREIGN KEY` constraints are recreated. Tables created with `--data-only` or raw CSV/table imports omit these constraints.
   - Note: Hibernate (`ddl-auto: update`) will NEVER add `IDENTITY` or sequences to existing columns, which leads to `ERROR: null value in column "id" violates not-null constraint` on inserts.

3. **Sizing & Connection Pools for Micro Tiers (e.g., db-f1-micro / shared cores)**:
   - Micro instances have strict RAM limits (~614 MB). To prevent Out-Of-Memory (OOM) crashes, pool bounds are configured conservatively:
     - `spring.datasource.hikari.maximum-pool-size=${DB_POOL_MAX_SIZE:2}`
     - `spring.datasource.hikari.minimum-idle=${DB_POOL_MIN_IDLE:1}`
   - Can be customized per environment using `DB_POOL_MAX_SIZE` and `DB_POOL_MIN_IDLE`.

## Verification boundaries

Automated H2 tests cover independent histories, replay, explicit adoption with data preservation, legacy translation conversion, concurrent provisioning, preservation of legacy schemas, and refusal of corrupt or failed history. Local packaged-JAR checks exercise the migration process and application startup. PostgreSQL/Neon adoption, Cloud Run execution and native-image migration support require separate validation before production use. No remote database or deployment is changed by the local checks.

