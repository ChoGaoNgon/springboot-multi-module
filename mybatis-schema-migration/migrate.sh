#!/usr/bin/env bash
# Run mybatis-migrations with connection injected via environment variables
# (no hardcoded secrets). The per-env .properties file is generated from env
# vars, used, and then removed (no secret left on disk).
#
# Usage: POSTGRES_HOST=.. POSTGRES_DB=.. POSTGRES_USER=.. POSTGRES_PASSWORD=.. \
#          ./migrate.sh <env> <command> [extra mvn args...]
# Examples:
#   ./migrate.sh production up
#   ./migrate.sh staging status
#   ./migrate.sh production down -Dmigration.down.steps=1
set -euo pipefail

ENV_NAME="${1:?env name required (staging|production|...)}"
COMMAND="${2:?command required (up|down|status|pending|new)}"
shift 2

: "${POSTGRES_HOST:?POSTGRES_HOST is required}"
: "${POSTGRES_DB:?POSTGRES_DB is required}"
: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/src/main/resources/co/jp/htkk/migration/environments/${ENV_NAME}.properties"
cleanup() { rm -f "$ENV_FILE"; }
trap cleanup EXIT

cat > "$ENV_FILE" <<EOF
time_zone=GMT+0:00
script_char_set=UTF-8
driver=org.postgresql.Driver
url=jdbc:postgresql://${POSTGRES_HOST}:${POSTGRES_PORT}/${POSTGRES_DB}
username=${POSTGRES_USER}
password=${POSTGRES_PASSWORD}
send_full_script=false
delimiter=;
full_line_delimiter=false
auto_commit=false
changelog=CHANGELOG
EOF

cd "$SCRIPT_DIR/.."
mvn -pl mybatis-schema-migration "migration:${COMMAND}" -Dmigration.env="${ENV_NAME}" "$@"
