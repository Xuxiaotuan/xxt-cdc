#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

SOURCE_CONTAINER="${SOURCE_CONTAINER:-cdc-source-mysql}"
TARGET_CONTAINER="${TARGET_CONTAINER:-cdc-target-mysql}"
CDC_CONTAINER="${CDC_CONTAINER:-mysql-cdc-service}"
SOURCE_DB="${SOURCE_DB:-test}"
TARGET_DB="${TARGET_DB:-test_target}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-password}"
DEMO_TABLE="${DEMO_TABLE:-cdc_demo_events}"
SKIP_BUILD="${SKIP_BUILD:-false}"

log() {
  printf '[demo] %s\n' "$*"
}

mysql_exec() {
  local container="$1"
  local database="$2"
  local sql="$3"
  docker exec "$container" mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$database" -e "$sql"
}

wait_for_healthy() {
  local container="$1"
  local attempts="${2:-90}"

  for _ in $(seq 1 "$attempts"); do
    local status
    status="$(docker inspect --format='{{.State.Health.Status}}' "$container" 2>/dev/null || true)"
    if [[ "$status" == "healthy" ]]; then
      log "$container is healthy"
      return 0
    fi
    sleep 2
  done

  log "$container did not become healthy in time"
  docker logs --tail=120 "$container" 2>/dev/null || true
  return 1
}

wait_for_consistency() {
  local attempts="${1:-60}"

  for _ in $(seq 1 "$attempts"); do
    if TABLES="$DEMO_TABLE" bash scripts/check-consistency.sh >/tmp/xxt-cdc-demo-consistency.log 2>&1; then
      cat /tmp/xxt-cdc-demo-consistency.log
      return 0
    fi
    sleep 2
  done

  cat /tmp/xxt-cdc-demo-consistency.log 2>/dev/null || true
  log "consistency check did not pass in time"
  docker logs --tail=120 "$CDC_CONTAINER" 2>/dev/null || true
  return 1
}

log "Validating docker compose file"
docker compose config >/dev/null

if [[ "$SKIP_BUILD" != "true" ]]; then
  log "Building assembly jar for Docker image"
  sbt assembly
fi

log "Starting source, target, and CDC service"
docker compose up -d source-mysql target-mysql cdc-service

wait_for_healthy "$SOURCE_CONTAINER" 90
wait_for_healthy "$TARGET_CONTAINER" 90
wait_for_healthy "$CDC_CONTAINER" 120

log "Resetting demo table on source and target"
mysql_exec "$SOURCE_CONTAINER" "$SOURCE_DB" "TRUNCATE TABLE $DEMO_TABLE;"
mysql_exec "$TARGET_CONTAINER" "$TARGET_DB" "TRUNCATE TABLE $DEMO_TABLE;"

log "Writing demo changes to source MySQL"
mysql_exec "$SOURCE_CONTAINER" "$SOURCE_DB" "
INSERT INTO $DEMO_TABLE (id, event_key, event_value) VALUES
  (1, 'demo-1', 'created'),
  (2, 'demo-2', 'created'),
  (3, 'demo-3', 'created');
UPDATE $DEMO_TABLE SET event_value = 'updated' WHERE id = 2;
DELETE FROM $DEMO_TABLE WHERE id = 3;
INSERT INTO $DEMO_TABLE (id, event_key, event_value) VALUES
  (4, 'demo-4', 'created');
"

log "Waiting for CDC runtime to apply demo changes"
wait_for_consistency 60

cat <<EOF

Demo completed.

Useful endpoints:
  Health:  http://localhost:8080/health
  Status:  http://localhost:8080/status
  Metrics: http://localhost:8080/metrics

To stop the demo environment:
  docker compose down
EOF
