#!/usr/bin/env bash
set -euo pipefail

SOURCE_CONTAINER="${SOURCE_CONTAINER:-cdc-source-mysql}"
TARGET_CONTAINER="${TARGET_CONTAINER:-cdc-target-mysql}"
SOURCE_DB="${SOURCE_DB:-test}"
TARGET_DB="${TARGET_DB:-test_target}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-password}"
TABLES="${TABLES:-users orders}"

mysql_exec() {
  local container="$1"
  local database="$2"
  local sql="$3"
  docker exec "$container" mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" --batch --skip-column-names "$database" -e "$sql"
}

checksum_table() {
  local container="$1"
  local database="$2"
  local table="$3"
  mysql_exec "$container" "$database" "CHECKSUM TABLE $table;" | awk '{print $2}'
}

echo "Checking consistency between $SOURCE_CONTAINER/$SOURCE_DB and $TARGET_CONTAINER/$TARGET_DB"

for table in $TABLES; do
  source_count="$(mysql_exec "$SOURCE_CONTAINER" "$SOURCE_DB" "SELECT COUNT(*) FROM $table;")"
  target_count="$(mysql_exec "$TARGET_CONTAINER" "$TARGET_DB" "SELECT COUNT(*) FROM $table;")"
  source_checksum="$(checksum_table "$SOURCE_CONTAINER" "$SOURCE_DB" "$table")"
  target_checksum="$(checksum_table "$TARGET_CONTAINER" "$TARGET_DB" "$table")"

  printf '%-16s source_count=%s target_count=%s source_checksum=%s target_checksum=%s\n' \
    "$table" "$source_count" "$target_count" "$source_checksum" "$target_checksum"

  if [[ "$source_count" != "$target_count" || "$source_checksum" != "$target_checksum" ]]; then
    echo "Consistency check failed for table: $table" >&2
    exit 1
  fi
done

echo "Consistency check passed."
