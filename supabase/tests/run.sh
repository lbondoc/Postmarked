#!/bin/bash
# Applies every migration to a scratch database and runs the SQL tests against it.
# Needs a Postgres server with PostGIS; connects using the usual PG* variables
# (PGHOST, PGUSER, PGPASSWORD) and must be allowed to create databases and roles.
set -euo pipefail
cd "$(dirname "$0")/.."

DB=postmarked_test
run() { psql -X -q -v ON_ERROR_STOP=1 "$@"; }

run -d postgres -c "drop database if exists $DB" -c "create database $DB"
export PGDATABASE=$DB PGOPTIONS='--client-min-messages=warning'
run -f tests/supabase_stub.sql
for f in migrations/*.sql tests/*_test.sql; do
  echo "== $f"
  run -f "$f"
done
echo "database tests passed"
