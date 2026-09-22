#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEPLOY_HOST="${DEPLOY_HOST:-root@123.56.22.101}"
REMOTE_ROOT="${REMOTE_ROOT:-/opt/training-plan}"
ALLOW_DIRTY="${ALLOW_DIRTY:-0}"
SKIP_TESTS="${SKIP_TESTS:-0}"

log() { printf '[deploy-server] %s\n' "$*"; }
fail() { printf '[deploy-server] ERROR: %s\n' "$*" >&2; exit 1; }

for command_name in git ssh scp tar java curl; do
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
[[ "$(java_major)" -ge 17 ]] || fail "JDK 17 or newer is required"

if [[ "$ALLOW_DIRTY" != "1" ]] && [[ -n "$(git -C "$PROJECT_ROOT" status --porcelain)" ]]; then
  fail "working tree is dirty; commit changes first or deliberately set ALLOW_DIRTY=1"
fi

git_sha="$(git -C "$PROJECT_ROOT" rev-parse --short=12 HEAD)"
release_id="server-$(date -u +%Y%m%dT%H%M%SZ)-$git_sha"
tmp_base="${TMPDIR:-/tmp}"
tmp_dir="$(mktemp -d "$tmp_base/training-plan-server-deploy.XXXXXX")"
cleanup() {
  case "$tmp_dir" in
    "$tmp_base"/training-plan-server-deploy.*) rm -rf -- "$tmp_dir" ;;
  esac
}
trap cleanup EXIT

if [[ "$SKIP_TESTS" != "1" ]]; then
  log "testing and packaging Spring Boot server"
  (cd "$PROJECT_ROOT/server" && ./mvnw clean package)
else
  log "SKIP_TESTS=1: reusing existing server artifact"
fi
server_jar="$(find "$PROJECT_ROOT/server/target" -maxdepth 1 -type f \
  -name '*.jar' ! -name '*.original' | head -n 1)"
[[ -f "$server_jar" ]] || fail "server JAR not found"

bundle="$tmp_dir/bundle"
mkdir -p "$bundle/artifacts"
cp "$server_jar" "$bundle/artifacts/"
cp "$PROJECT_ROOT/deploy/Dockerfile.server" "$bundle/"
cp "$PROJECT_ROOT/deploy/docker-compose.yml" "$bundle/"
cp "$PROJECT_ROOT/deploy/remote-deploy-server.sh" "$bundle/"
printf 'component=server\nrelease=%s\ngit_sha=%s\nbuilt_at=%s\n' \
  "$release_id" "$git_sha" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$bundle/RELEASE"

archive="$tmp_dir/$release_id.tar.gz"
COPYFILE_DISABLE=1 tar -C "$bundle" -czf "$archive" .
log "uploading $release_id"
ssh "$DEPLOY_HOST" "mkdir -p '$REMOTE_ROOT/server-releases' '$REMOTE_ROOT/incoming' '$REMOTE_ROOT/backups'"
scp "$archive" "$DEPLOY_HOST:$REMOTE_ROOT/incoming/$release_id.tar.gz"
ssh "$DEPLOY_HOST" "set -eu
  release='$REMOTE_ROOT/server-releases/$release_id'
  test ! -e \"\$release\"
  mkdir -p \"\$release\"
  tar -xzf '$REMOTE_ROOT/incoming/$release_id.tar.gz' -C \"\$release\"
  rm -f '$REMOTE_ROOT/incoming/$release_id.tar.gz'
  chmod 750 \"\$release/remote-deploy-server.sh\"
  APP_ROOT='$REMOTE_ROOT' APP_ENV_FILE='$REMOTE_ROOT/shared/app.env' \
    \"\$release/remote-deploy-server.sh\" \"\$release\""

curl --fail --silent --show-error --max-time 15 \
  https://songtop.xyz/planapi/api/system/health >/dev/null
log "server deployment complete ($release_id)"
