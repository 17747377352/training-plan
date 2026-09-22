#!/usr/bin/env bash
set -Eeuo pipefail

APP_ROOT="${APP_ROOT:-/opt/training-plan}"
RELEASE_DIR="${1:-}"
ENV_FILE="${APP_ENV_FILE:-$APP_ROOT/shared/app.env}"
NGINX_SITE="${NGINX_SITE:-/etc/nginx/sites-available/songtop.xyz}"
NGINX_ENABLED="${NGINX_ENABLED:-/etc/nginx/sites-enabled/songtop.xyz}"
WEB_ROOT="${WEB_ROOT:-/var/www/plan}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-mysql-server}"
REDIS_CONTAINER="${REDIS_CONTAINER:-redis-server}"
BOOTSTRAP_SWAP="${BOOTSTRAP_SWAP:-1}"

log() { printf '[remote-deploy] %s\n' "$*"; }
fail() { printf '[remote-deploy] ERROR: %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || fail "must run as root"
[[ -n "$RELEASE_DIR" ]] || fail "usage: remote-deploy.sh RELEASE_DIR"
RELEASE_DIR="$(readlink -f "$RELEASE_DIR")"
case "$RELEASE_DIR" in
  "$APP_ROOT"/releases/*) ;;
  *) fail "release directory is outside $APP_ROOT/releases" ;;
esac
[[ -f "$RELEASE_DIR/docker-compose.yml" ]] || fail "release is incomplete"
[[ -f "$ENV_FILE" ]] || fail "missing $ENV_FILE"

env_mode="$(stat -c '%a' "$ENV_FILE")"
[[ "$env_mode" == "600" ]] || fail "$ENV_FILE mode must be 600 (current: $env_mode)"

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

required=(
  SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD
  SPRING_DATA_REDIS_PASSWORD COLLECTOR_REDIS_PASSWORD
  JWT_SECRET TOKEN_CIPHER_KEY COLLECTOR_SERVICE_TOKEN COLLECTOR_SERVER_TOKEN
)
for name in "${required[@]}"; do
  [[ -n "${!name:-}" ]] || fail "$name is empty in $ENV_FILE"
done
[[ "$SPRING_DATA_REDIS_PASSWORD" == "$COLLECTOR_REDIS_PASSWORD" ]] \
  || fail "backend and collector Redis passwords differ"
[[ "$COLLECTOR_SERVICE_TOKEN" == "$COLLECTOR_SERVER_TOKEN" ]] \
  || fail "collector service tokens differ"
[[ "${#JWT_SECRET}" -ge 32 ]] || fail "JWT_SECRET must contain at least 32 characters"
[[ "${#COLLECTOR_SERVICE_TOKEN}" -ge 32 ]] \
  || fail "collector service token must contain at least 32 characters"
if ! cipher_key_length="$(printf '%s' "$TOKEN_CIPHER_KEY" | base64 -d 2>/dev/null | wc -c)"; then
  fail "TOKEN_CIPHER_KEY is not valid Base64"
fi
cipher_key_length="${cipher_key_length//[[:space:]]/}"
[[ "$cipher_key_length" == "32" ]] || fail "TOKEN_CIPHER_KEY must decode to exactly 32 bytes"

command -v docker >/dev/null || fail "docker is not installed"
docker compose version >/dev/null || fail "docker compose is unavailable"
command -v curl >/dev/null || fail "curl is not installed"
command -v nginx >/dev/null || fail "nginx is not installed"

if [[ "$BOOTSTRAP_SWAP" == "1" ]] && [[ -z "$(swapon --show --noheadings)" ]]; then
  log "no swap detected; creating a 2 GiB swap file for image builds"
  [[ ! -e /swapfile ]] || fail "/swapfile exists but is not active; inspect it manually"
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile >/dev/null
  swapon /swapfile
  grep -qF '/swapfile none swap sw 0 0' /etc/fstab \
    || printf '/swapfile none swap sw 0 0\n' >> /etc/fstab
fi

release_id="$(basename "$RELEASE_DIR")"
export RELEASE_ID="$release_id" APP_ENV_FILE="$ENV_FILE"
compose=(docker compose --env-file "$ENV_FILE" -f "$RELEASE_DIR/docker-compose.yml")

mkdir -p "$APP_ROOT/backups" /var/www /etc/nginx/backups
backup_file="$APP_ROOT/backups/training_plan-before-$release_id.sql.gz"
docker inspect "$MYSQL_CONTAINER" >/dev/null 2>&1 || fail "missing MySQL container $MYSQL_CONTAINER"
docker inspect "$REDIS_CONTAINER" >/dev/null 2>&1 || fail "missing Redis container $REDIS_CONTAINER"

log "validating database and Redis application credentials"
docker exec -e MYSQL_PWD="$SPRING_DATASOURCE_PASSWORD" "$MYSQL_CONTAINER" \
  mysql --protocol=TCP -h127.0.0.1 -u"$SPRING_DATASOURCE_USERNAME" \
  --batch --skip-column-names training_plan -e 'SELECT 1' \
  | grep -qx '1' || fail "application database credentials cannot access training_plan"
docker exec -e REDISCLI_AUTH="$SPRING_DATA_REDIS_PASSWORD" "$REDIS_CONTAINER" \
  redis-cli -h 127.0.0.1 -p 6379 -n 5 ping \
  | grep -qx 'PONG' || fail "application Redis credentials are invalid"

log "creating database backup: $backup_file"
docker exec "$MYSQL_CONTAINER" sh -lc \
  'exec mysqldump --single-transaction --quick --routines --triggers -uroot -p"$MYSQL_ROOT_PASSWORD" training_plan' \
  | gzip -1 > "$backup_file"
[[ -s "$backup_file" ]] || fail "database backup is empty"
chmod 600 "$backup_file"

previous_release=""
had_current=0
[[ -e "$APP_ROOT/current" || -L "$APP_ROOT/current" ]] && had_current=1
[[ -L "$APP_ROOT/current" ]] && previous_release="$(readlink -f "$APP_ROOT/current")"
previous_web=""
had_web=0
[[ -e "$WEB_ROOT" || -L "$WEB_ROOT" ]] && had_web=1
[[ -L "$WEB_ROOT" ]] && previous_web="$(readlink -f "$WEB_ROOT")"
bootstrap_web_backup=""
nginx_backup=""
nginx_existed=0
[[ -f "$NGINX_SITE" ]] && nginx_existed=1

rollback_containers() {
  log "rolling back containers"
  if [[ -n "$previous_release" && -f "$previous_release/docker-compose.yml" ]]; then
    local previous_id
    previous_id="$(basename "$previous_release")"
    RELEASE_ID="$previous_id" APP_ENV_FILE="$ENV_FILE" \
      docker compose --env-file "$ENV_FILE" -f "$previous_release/docker-compose.yml" up -d --remove-orphans || true
  else
    "${compose[@]}" down || true
  fi
}

restore_files() {
  if [[ -n "$previous_web" ]]; then
    ln -sfn "$previous_web" "$WEB_ROOT.rollback"
    mv -Tf "$WEB_ROOT.rollback" "$WEB_ROOT"
  elif [[ -n "$bootstrap_web_backup" && -d "$bootstrap_web_backup" ]]; then
    [[ -L "$WEB_ROOT" ]] && unlink "$WEB_ROOT"
    mv "$bootstrap_web_backup" "$WEB_ROOT"
  elif [[ "$had_web" == "0" && -L "$WEB_ROOT" ]]; then
    unlink "$WEB_ROOT"
  fi
  if [[ -n "$previous_release" ]]; then
    ln -sfn "$previous_release" "$APP_ROOT/current.rollback"
    mv -Tf "$APP_ROOT/current.rollback" "$APP_ROOT/current"
  elif [[ "$had_current" == "0" && -L "$APP_ROOT/current" ]]; then
    unlink "$APP_ROOT/current"
  fi
  if [[ -n "$nginx_backup" && -f "$nginx_backup" ]]; then
    install -m 644 "$nginx_backup" "$NGINX_SITE"
    nginx -t >/dev/null 2>&1 && systemctl reload nginx || true
  elif [[ "$nginx_existed" == "0" ]]; then
    [[ -L "$NGINX_ENABLED" ]] && unlink "$NGINX_ENABLED"
    rm -f -- "$NGINX_SITE"
    nginx -t >/dev/null 2>&1 && systemctl reload nginx || true
  fi
}

wait_for_url() {
  local url="$1" attempts="${2:-90}"
  for ((i=1; i<=attempts; i++)); do
    if curl --fail --silent --show-error --max-time 3 "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  return 1
}

log "building release images"
"${compose[@]}" build
log "starting release containers"
"${compose[@]}" up -d --remove-orphans

if ! wait_for_url http://127.0.0.1:18080/api/system/health 90 \
  || ! wait_for_url http://127.0.0.1:18090/health 45; then
  "${compose[@]}" ps || true
  "${compose[@]}" logs --tail=100 || true
  rollback_containers
  fail "health check failed; previous containers restored when available"
fi

log "installing and validating Nginx configuration"
if [[ -f "$NGINX_SITE" ]]; then
  nginx_backup="/etc/nginx/backups/songtop.xyz.before-$release_id"
  cp -a "$NGINX_SITE" "$nginx_backup"
fi
install -m 644 "$RELEASE_DIR/nginx/songtop.xyz.conf" "$NGINX_SITE"
ln -sfn "$NGINX_SITE" "$NGINX_ENABLED"
if ! nginx -t; then
  restore_files
  rollback_containers
  fail "Nginx validation failed"
fi

log "switching release symlinks"
if [[ -e "$WEB_ROOT" && ! -L "$WEB_ROOT" ]]; then
  bootstrap_web_backup="${WEB_ROOT}.bootstrap-$release_id"
  mv "$WEB_ROOT" "$bootstrap_web_backup"
fi
ln -sfn "$RELEASE_DIR/web" "$WEB_ROOT.next"
mv -Tf "$WEB_ROOT.next" "$WEB_ROOT"
ln -sfn "$RELEASE_DIR" "$APP_ROOT/current.next"
mv -Tf "$APP_ROOT/current.next" "$APP_ROOT/current"
systemctl reload nginx

if ! wait_for_url https://songtop.xyz/plan/ 15 \
  || ! wait_for_url https://songtop.xyz/planapi/api/system/health 15; then
  restore_files
  rollback_containers
  fail "public health check failed; previous release restored"
fi

log "deployment succeeded: $release_id"
log "database backup retained at $backup_file"
"${compose[@]}" ps
