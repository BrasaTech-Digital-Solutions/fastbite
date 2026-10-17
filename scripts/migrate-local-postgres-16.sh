#!/usr/bin/env bash
# Migrates local PostgreSQL 17 data from volume fastbite_postgres-environments
# to PostgreSQL 16 in volume fastbite_postgres16-environments without touching
# the original volume.
set -euo pipefail

# Prevent Git Bash / MSYS from rewriting POSIX container paths and volume mounts
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL="*"

ROOT=$(cd "$(dirname "$0")/.." && (pwd -W 2>/dev/null || pwd))
DC="docker compose -f $ROOT/docker-compose.yml"

OLD_VOL="${OLD_VOL:-fastbite_postgres-environments}"
NEW_VOL="${NEW_VOL:-fastbite_postgres16-environments}"
TMP_VOL="fastbite_pg17_migrate_copy_$$"
TMP_PG17="fastbite-pg17-migrate-$$"
DUMP_FILE=$(mktemp -t fastbite-pg16-dump-XXXXXX.sql)

PG17_IMAGE="postgres:17.11"
ALPINE_IMAGE="alpine:3.21"

cleanup() {
  docker rm -f "$TMP_PG17" >/dev/null 2>&1 || true
  docker volume rm -f "$TMP_VOL" >/dev/null 2>&1 || true
  rm -f "$DUMP_FILE"
}
trap cleanup EXIT

if ! docker volume inspect "$OLD_VOL" >/dev/null 2>&1; then
  echo "Source volume $OLD_VOL does not exist; nothing to migrate."
  exit 1
fi

echo "== Stopping application containers and starting postgres (16.9) on $NEW_VOL..."
$DC stop production sandbox >/dev/null 2>&1 || true
$DC up -d postgres

echo "Waiting for postgres (16.9) to become ready..."
WAIT_DEADLINE=$((SECONDS + 60))
while [ $SECONDS -lt $WAIT_DEADLINE ]; do
  if $DC exec -T postgres pg_isready -U fastbite -d fastbite_sandbox >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

# Guard: stop before loading if the new volume already has user data
has_data() {
  local db="$1"
  local exists
  exists=$($DC exec -T postgres psql -U fastbite -d "$db" -v ON_ERROR_STOP=1 -tAc \
    "SELECT to_regclass('public.tenant_locations') IS NOT NULL;" | tr -d ' \r\n')
  if [ "$exists" = "t" ]; then
    local count
    count=$($DC exec -T postgres psql -U fastbite -d "$db" -v ON_ERROR_STOP=1 -tAc \
      "SELECT count(*) FROM public.tenant_locations;" | tr -d ' \r\n')
    if [ "${count:-0}" -gt 0 ]; then
      return 0
    fi
  fi
  return 1
}

if has_data "fastbite_production" || has_data "fastbite_sandbox"; then
  echo "Target volume $NEW_VOL already contains data in fastbite_production or fastbite_sandbox."
  echo "Refusing to overwrite existing data."
  exit 1
fi

echo "== Copying $OLD_VOL (read-only) to temporary volume $TMP_VOL..."
docker volume create "$TMP_VOL" >/dev/null
docker run --rm \
  -v "$OLD_VOL:/from:ro" \
  -v "$TMP_VOL:/to" \
  "$ALPINE_IMAGE" cp -a /from/. /to/

echo "== Starting temporary $PG17_IMAGE container on $TMP_VOL..."
docker run -d --name "$TMP_PG17" \
  -e POSTGRES_USER=fastbite \
  -e POSTGRES_PASSWORD=fastbite \
  -v "$TMP_VOL:/var/lib/postgresql/data" \
  "$PG17_IMAGE" postgres -c max_connections=25 >/dev/null

WAIT_DEADLINE=$((SECONDS + 60))
while [ $SECONDS -lt $WAIT_DEADLINE ]; do
  if docker exec "$TMP_PG17" pg_isready -U fastbite -d fastbite_sandbox >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

echo "== Running pg_dumpall from temporary PostgreSQL 17 container..."
# Strip PostgreSQL 17's `SET transaction_timeout = 0;` and psql `\restrict` / `\unrestrict`
# meta-commands (which PostgreSQL 16.9 rejects), and remove redundant CREATE ROLE / CREATE DATABASE
# statements for bootstrap objects already created on the PostgreSQL 16 volume.
docker exec "$TMP_PG17" pg_dumpall -U fastbite \
  | sed -E \
      -e '/^SET transaction_timeout =/d' \
      -e '/^\\(un)?restrict /d' \
      -e '/^CREATE ROLE fastbite;/d' \
      -e '/^CREATE DATABASE (fastbite|fastbite_production|fastbite_sandbox) /d' \
  > "$DUMP_FILE"

echo "== Preparing clean target databases in PostgreSQL 16 and loading pg_dumpall dump..."
for db in fastbite_production fastbite_sandbox; do
  $DC exec -T postgres psql -U fastbite -d postgres -v ON_ERROR_STOP=1 -c "DROP DATABASE IF EXISTS $db WITH (FORCE);" >/dev/null
  $DC exec -T postgres psql -U fastbite -d postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE $db;" >/dev/null
done

$DC exec -T postgres psql -U fastbite -d postgres -v ON_ERROR_STOP=1 < "$DUMP_FILE" >/dev/null

echo "== Verifying row counts of every table in public and tenant_* schemas..."
MISMATCH=0
compare_db() {
  local db="$1"
  local sql="
    SELECT n.nspname || '.' || c.relname,
           (xpath('/row/cnt/text()', query_to_xml(format('SELECT count(*) AS cnt FROM %I.%I', n.nspname, c.relname), false, true, '')))[1]::text::bigint
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE c.relkind = 'r'
      AND (n.nspname = 'public' OR n.nspname LIKE 'tenant_%')
    ORDER BY n.nspname, c.relname;
  "
  local src_out dst_out
  src_out=$(docker exec "$TMP_PG17" psql -U fastbite -d "$db" -v ON_ERROR_STOP=1 -tAF '|' -c "$sql" | tr -d '\r')
  dst_out=$($DC exec -T postgres psql -U fastbite -d "$db" -v ON_ERROR_STOP=1 -tAF '|' -c "$sql" | tr -d '\r')

  if ! printf '%s\n---\n%s\n' "$src_out" "$dst_out" | awk -F'|' -v db="$db" '
    $0 == "---" { in_dst = 1; next }
    NF >= 2 && $1 != "" {
      if (!in_dst) {
        src[$1] = $2
        order[++n] = $1
      } else {
        dst[$1] = $2
        if (!($1 in src)) {
          order[++n] = $1
        }
      }
    }
    END {
      if (n == 0) {
        printf "FAIL %-22s no tables found\n", db
        exit 1
      }
      mismatches = 0
      for (i = 1; i <= n; i++) {
        t = order[i]
        s = (t in src) ? src[t] : "missing"
        d = (t in dst) ? dst[t] : "missing"
        if (s == d) {
          printf "OK   %-22s %-52s %s rows\n", db, t, d
        } else {
          printf "FAIL %-22s %-52s PG17=%s PG16=%s\n", db, t, s, d
          mismatches++
        }
      }
      printf "SUMMARY %-19s %d tables verified, %d mismatches\n", db, n, mismatches
      exit (mismatches > 0 ? 1 : 0)
    }
  '; then
    MISMATCH=$((MISMATCH + 1))
  fi
}

compare_db "fastbite_production"
compare_db "fastbite_sandbox"

if [ "$MISMATCH" -ne 0 ]; then
  echo "ERROR: $MISMATCH database comparison mismatch(es) detected!"
  exit 1
fi

echo "== Migration to PostgreSQL 16 ($NEW_VOL) completed with 0 mismatches."
echo "Original volume $OLD_VOL was left untouched as a backup."
