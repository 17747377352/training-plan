#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEPLOY_HOST="${DEPLOY_HOST:-root@123.56.22.101}"
REMOTE_ROOT="${REMOTE_ROOT:-/opt/training-plan}"
ALLOW_DIRTY="${ALLOW_DIRTY:-0}"
SKIP_TESTS="${SKIP_TESTS:-0}"
BOOTSTRAP_SWAP="${BOOTSTRAP_SWAP:-1}"
DEPLOY_TARGET="${1:-all}"

case "$DEPLOY_TARGET" in
  web)
    exec "$PROJECT_ROOT/deploy/deploy-web.sh"
    ;;
  server)
    exec "$PROJECT_ROOT/deploy/deploy-server.sh"
    ;;
  all)
    ;;
  *)
    printf 'usage: %s [all|web|server]\n' "$0" >&2
    exit 2
    ;;
esac

log() { printf '[deploy] %s\n' "$*"; }
fail() { printf '[deploy] ERROR: %s\n' "$*" >&2; exit 1; }

for command_name in git ssh scp tar java npm uv; do
  command -v "$command_name" >/dev/null || fail "missing local command: $command_name"
done
[[ -x "$PROJECT_ROOT/server/mvnw" ]] || fail "server/mvnw is not executable"

java_major() {
  local version
  version="$(java -XshowSettings:properties -version 2>&1 \
    | awk -F'= ' '/java.specification.version/ {print $2; exit}')"
  [[ "$version" == 1.* ]] && version="${version#1.}"
  printf '%s' "${version%%.*}"
}

if [[ "$(java_major)" -lt 17 ]] && [[ -x /usr/libexec/java_home ]]; then
  export JAVA_HOME
  JAVA_HOME="$(/usr/libexec/java_home -v 17)"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
[[ "$(java_major)" -ge 17 ]] || fail "JDK 17 or newer is required to build the server"

if [[ "$ALLOW_DIRTY" != "1" ]] && [[ -n "$(git -C "$PROJECT_ROOT" status --porcelain)" ]]; then
  fail "working tree is dirty; commit changes first or deliberately set ALLOW_DIRTY=1"
fi

git_sha="$(git -C "$PROJECT_ROOT" rev-parse --short=12 HEAD)"
release_id="$(date -u +%Y%m%dT%H%M%SZ)-$git_sha"
tmp_base="${TMPDIR:-/tmp}"
tmp_dir="$(mktemp -d "$tmp_base/training-plan-deploy.XXXXXX")"

cleanup() {
  case "$tmp_dir" in
    "$tmp_base"/training-plan-deploy.*) rm -rf -- "$tmp_dir" ;;
  esac
}
trap cleanup EXIT

log "checking remote deployment environment"
ssh "$DEPLOY_HOST" "mkdir -p '$REMOTE_ROOT/releases' '$REMOTE_ROOT/incoming' '$REMOTE_ROOT/shared' '$REMOTE_ROOT/backups'"
if ! ssh "$DEPLOY_HOST" "test -f '$REMOTE_ROOT/shared/app.env'"; then
  scp "$PROJECT_ROOT/deploy/app.env.example" "$DEPLOY_HOST:$REMOTE_ROOT/shared/app.env.example"
  fail "remote secrets are not configured. Fill $REMOTE_ROOT/shared/app.env from app.env.example and chmod 600 it"
fi

if [[ "$SKIP_TESTS" != "1" ]]; then
  log "testing and packaging Spring Boot server"
  (cd "$PROJECT_ROOT/server" && ./mvnw clean package)

  log "testing and packaging Python collector"
  (cd "$PROJECT_ROOT/collector" && uv sync --frozen && uv run ruff check . && uv run pytest && uv build)

  log "installing and building web application"
  (cd "$PROJECT_ROOT/web" && npm ci --no-audit --no-fund \
    && VITE_BASE_PATH=/plan/ VITE_API_BASE_URL=/planapi npm run build)
else
  log "SKIP_TESTS=1: reusing existing build outputs"
fi

server_jar="$(find "$PROJECT_ROOT/server/target" -maxdepth 1 -type f -name '*.jar' ! -name '*.original' | head -n 1)"
collector_wheel="$(find "$PROJECT_ROOT/collector/dist" -maxdepth 1 -type f -name '*.whl' | head -n 1)"
[[ -f "$server_jar" ]] || fail "server JAR not found"
[[ -f "$collector_wheel" ]] || fail "collector wheel not found"
[[ -f "$PROJECT_ROOT/web/dist/index.html" ]] || fail "web build not found"

bundle="$tmp_dir/bundle"
mkdir -p "$bundle/artifacts" "$bundle/web" "$bundle/nginx"
cp "$server_jar" "$bundle/artifacts/"
cp "$collector_wheel" "$bundle/artifacts/"
cp -R "$PROJECT_ROOT/web/dist/." "$bundle/web/"
# nginx 以别的用户运行，静态文件必须对所有人可读：本地 umask 偏严时（曾出现 600 的
# garmin_pair_helper.py）浏览器会拿到 403，而部署过程本身不会报任何错。这里统一放开；
# a+rX 只给目录补执行位，不会把普通文件变成可执行。
chmod -R a+rX "$bundle/web"
# 明确断言一次：宁可部署失败，也不要上线后让用户看到 403
[[ -z "$(find "$bundle/web" ! -perm -o=r)" ]] || fail "web bundle contains files not readable by others"
cp "$PROJECT_ROOT/deploy/Dockerfile.server" "$bundle/"
cp "$PROJECT_ROOT/deploy/Dockerfile.collector" "$bundle/"
cp "$PROJECT_ROOT/deploy/docker-compose.yml" "$bundle/"
cp "$PROJECT_ROOT/deploy/remote-deploy.sh" "$bundle/"
cp "$PROJECT_ROOT/deploy/nginx/songtop.xyz.conf" "$bundle/nginx/"
printf 'release=%s\ngit_sha=%s\nbuilt_at=%s\n' \
  "$release_id" "$git_sha" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$bundle/RELEASE"

archive="$tmp_dir/$release_id.tar.gz"
COPYFILE_DISABLE=1 tar -C "$bundle" -czf "$archive" .
log "uploading release $release_id"
scp "$archive" "$DEPLOY_HOST:$REMOTE_ROOT/incoming/$release_id.tar.gz"

ssh "$DEPLOY_HOST" "set -eu
  release='$REMOTE_ROOT/releases/$release_id'
  test ! -e \"\$release\"
  mkdir -p \"\$release\"
  tar -xzf '$REMOTE_ROOT/incoming/$release_id.tar.gz' -C \"\$release\"
  rm -f '$REMOTE_ROOT/incoming/$release_id.tar.gz'
  chmod 750 \"\$release/remote-deploy.sh\"
  APP_ROOT='$REMOTE_ROOT' APP_ENV_FILE='$REMOTE_ROOT/shared/app.env' BOOTSTRAP_SWAP='$BOOTSTRAP_SWAP' \
    \"\$release/remote-deploy.sh\" \"\$release\""

log "verifying public endpoints from this machine"
curl --fail --silent --show-error --max-time 15 https://songtop.xyz/plan/ >/dev/null
curl --fail --silent --show-error --max-time 15 https://songtop.xyz/planapi/api/system/health >/dev/null
log "deployment complete: https://songtop.xyz/plan/ ($release_id)"
