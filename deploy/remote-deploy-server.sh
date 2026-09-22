#!/usr/bin/env bash
set -Eeuo pipefail

APP_ROOT="${APP_ROOT:-/opt/training-plan}"
RELEASE_DIR="${1:-}"
ENV_FILE="${APP_ENV_FILE:-$APP_ROOT/shared/app.env}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-mysql-server}"

log() { printf '[remote-server] %s\n' "$*"; }
fail() { printf '[remote-server] ERROR: %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || fail "must run as root"
[[ -n "$RELEASE_DIR" ]] || fail "usage: remote-deploy-server.sh RELEASE_DIR"
RELEASE_DIR="$(readlink -f "$RELEASE_DIR")"
case "$RELEASE_DIR" in
  "$APP_ROOT"/server-releases/*) ;;
  *) fail "release directory is outside $APP_ROOT/server-releases" ;;
esac
[[ -f "$RELEASE_DIR/docker-compose.yml" && -f "$RELEASE_DIR/Dockerfile.server" ]] \
  || fail "server release is incomplete"
[[ -f "$ENV_FILE" ]] || fail "missing $ENV_FILE"
[[ "$(stat -c '%a' "$ENV_FILE")" == "600" ]] || fail "$ENV_FILE mode must be 600"

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a
required=(SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD JWT_SECRET TOKEN_CIPHER_KEY)
for name in "${required[@]}"; do
  [[ -n "${!name:-}" ]] || fail "$name is empty in $ENV_FILE"
done

release_id="$(basename "$RELEASE_DIR")"
export RELEASE_ID="$release_id" APP_ENV_FILE="$ENV_FILE"
compose=(docker compose --env-file "$ENV_FILE" -f "$RELEASE_DIR/docker-compose.yml")
previous_image="$(docker inspect --format '{{.Config.Image}}' training-plan-server 2>/dev/null)"
[[ -n "$previous_image" ]] || fail "running server container was not found"

mkdir -p "$APP_ROOT/backups"
backup_file="$APP_ROOT/backups/training_plan-before-$release_id.sql.gz"
log "creating database backup"
docker exec "$MYSQL_CONTAINER" sh -lc \
  'exec mysqldump --single-transaction --quick --routines --triggers -uroot -p"$MYSQL_ROOT_PASSWORD" training_plan' \
  | gzip -1 > "$backup_file"
[[ -s "$backup_file" ]] || fail "database backup is empty"
chmod 600 "$backup_file"

wait_for_health() {
  for ((i=1; i<=75; i++)); do
    if curl --fail --silent --connect-timeout 1 --max-time 1 \
      http://127.0.0.1:18080/api/system/health >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

rollback() {
  log "rolling back to $previous_image"
  local rollback_tag="${release_id}-rollback"
  docker tag "$previous_image" "training-plan-server:$rollback_tag"
  RELEASE_ID="$rollback_tag" APP_ENV_FILE="$ENV_FILE" \
    docker compose --env-file "$ENV_FILE" -f "$RELEASE_DIR/docker-compose.yml" \
    up -d --no-deps server || true
  wait_for_health || true
}
started=0
abort_deployment() {
  trap - HUP INT TERM
  [[ "$started" == "1" ]] && rollback
  fail "server deployment interrupted"
}
trap abort_deployment HUP INT TERM

log "building server image"
"${compose[@]}" build server
log "restarting server"
"${compose[@]}" up -d --no-deps server
started=1
if ! wait_for_health; then
  "${compose[@]}" logs --tail=120 server || true
  rollback
  fail "server health check failed"
fi
if ! curl --fail --silent --connect-timeout 3 --max-time 10 \
  https://songtop.xyz/planapi/api/system/health >/dev/null; then
  rollback
  fail "public server check failed"
fi
ln -sfn "$RELEASE_DIR" "$APP_ROOT/current-server.next"
mv -Tf "$APP_ROOT/current-server.next" "$APP_ROOT/current-server"
trap - HUP INT TERM
log "deployment succeeded: $release_id"
