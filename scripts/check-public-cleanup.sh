#!/usr/bin/env bash
# Proves on PostgreSQL that V22 refuses to drop restaurant data from the public schema.
#
#   bash scripts/check-public-cleanup.sh <dump.sql>
#
# <dump.sql> is a plain pg_dump of a database from before V22 (public still holding the restaurant tables),
# for example taken with:
#   docker compose exec -T postgres pg_dump -U fastbite --no-owner fastbite_production > pre-v22.sql
# Keep such dumps outside the repository: they contain password hashes.
#
# The check restores the dump into a throwaway database, puts one order into public.orders, runs the
# migration job of the current image (build it first with `docker compose build`) against it, and expects:
# the job fails naming public.orders, and public keeps all its tables. The throwaway database is then dropped.
# The real databases are never touched.
set -uo pipefail

DUMP="${1:?usage: check-public-cleanup.sh <pre-V22 pg_dump.sql>}"
DC="docker compose"
DB=fastbite_v22_guard
FAILED=0

psql_admin() { $DC exec -T postgres psql -v ON_ERROR_STOP=1 -U fastbite -d postgres -qtAc "$1"; }
psql_db() { $DC exec -T postgres psql -v ON_ERROR_STOP=1 -U fastbite -d "$DB" -qtAc "$1"; }
cleanup() { psql_admin "DROP DATABASE IF EXISTS $DB" >/dev/null 2>&1; }
trap cleanup EXIT

cleanup
psql_admin "CREATE DATABASE $DB" >/dev/null
$DC exec -T postgres psql -v ON_ERROR_STOP=1 -U fastbite -d "$DB" -q < "$DUMP" >/dev/null || { echo "FAIL could not restore $DUMP"; exit 1; }

BEFORE=$(psql_db "select count(*) from information_schema.tables where table_schema = 'public'")
if [ "$(psql_db "select count(*) from information_schema.tables where table_schema = 'public' and table_name = 'orders'")" != "1" ]; then
  echo "FAIL $DUMP is not from before V22: public has no orders table"; exit 1
fi
psql_db "INSERT INTO public.orders (id, order_number, created_at, updated_at, status, total, payment_status, order_channel, order_language) VALUES ('v22-guard', 1, now(), now(), 0, 1.00, 0, 0, 'en')" >/dev/null

OUTPUT=$($DC run --rm --no-deps -e DB_URL="jdbc:postgresql://postgres:5432/$DB" migrate-sandbox 2>&1)
CODE=$?

if [ "$CODE" -ne 0 ] && echo "$OUTPUT" | grep -q "Refusing to drop public.orders: it holds 1 row"; then
  echo "OK   migration job refused: $(echo "$OUTPUT" | grep -o 'Refusing to drop public.orders[^.]*' | head -1)"
else
  echo "FAIL migration job exit code $CODE, expected a refusal naming public.orders"; FAILED=1
  echo "$OUTPUT" | tail -20
fi

AFTER=$(psql_db "select count(*) from information_schema.tables where table_schema = 'public'")
if [ "$AFTER" = "$BEFORE" ]; then
  echo "OK   public still has all its $AFTER tables"
else
  echo "FAIL public had $BEFORE tables and now has $AFTER"; FAILED=1
fi
V22=$(psql_db "select count(*) from public.flyway_schema_history where version = '22'")
if [ "$V22" = "0" ]; then
  echo "OK   V22 is not recorded (the whole migration rolled back)"
else
  echo "FAIL V22 is recorded $V22 time(s)"; FAILED=1
fi

exit $FAILED
