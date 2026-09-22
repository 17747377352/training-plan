#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEPLOY_HOST="${DEPLOY_HOST:-root@123.56.22.101}"
REMOTE_ROOT="${REMOTE_ROOT:-/opt/training-plan}"
ALLOW_DIRTY="${ALLOW_DIRTY:-0}"

log() { printf '[deploy-web] %s\n' "$*"; }
fail() { printf '[deploy-web] ERROR: %s\n' "$*" >&2; exit 1; }

for command_name in git ssh scp tar npm curl; do
  command -v "$command_name" >/dev/null || fail "missing local command: $command_name"
done
if [[ "$ALLOW_DIRTY" != "1" ]] && [[ -n "$(git -C "$PROJECT_ROOT" status --porcelain)" ]]; then
  fail "working tree is dirty; commit changes first or deliberately set ALLOW_DIRTY=1"
fi

git_sha="$(git -C "$PROJECT_ROOT" rev-parse --short=12 HEAD)"
release_id="web-$(date -u +%Y%m%dT%H%M%SZ)-$git_sha"
tmp_base="${TMPDIR:-/tmp}"
tmp_dir="$(mktemp -d "$tmp_base/training-plan-web-deploy.XXXXXX")"
cleanup() {
  case "$tmp_dir" in
    "$tmp_base"/training-plan-web-deploy.*) rm -rf -- "$tmp_dir" ;;
  esac
}
trap cleanup EXIT

log "building web application"
(cd "$PROJECT_ROOT/web" && npm ci --no-audit --no-fund \
  && VITE_BASE_PATH=/plan/ VITE_API_BASE_URL=/planapi npm run build)
[[ -f "$PROJECT_ROOT/web/dist/index.html" ]] || fail "web build not found"

bundle="$tmp_dir/bundle"
mkdir -p "$bundle/web"
cp -R "$PROJECT_ROOT/web/dist/." "$bundle/web/"
# nginx 以别的用户运行，静态文件必须对所有人可读：本地 umask 偏严时（曾出现 600 的
# garmin_pair_helper.py）浏览器会拿到 403，而部署过程本身不会报任何错。这里统一放开；
# a+rX 只给目录补执行位，不会把普通文件变成可执行。
chmod -R a+rX "$bundle/web"
# 明确断言一次：宁可部署失败，也不要上线后让用户看到 403
[[ -z "$(find "$bundle/web" ! -perm -o=r)" ]] || fail "web bundle contains files not readable by others"
cp "$PROJECT_ROOT/deploy/remote-deploy-web.sh" "$bundle/"
printf 'component=web\nrelease=%s\ngit_sha=%s\nbuilt_at=%s\n' \
  "$release_id" "$git_sha" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$bundle/RELEASE"

archive="$tmp_dir/$release_id.tar.gz"
COPYFILE_DISABLE=1 tar -C "$bundle" -czf "$archive" .
log "uploading $release_id"
ssh "$DEPLOY_HOST" "mkdir -p '$REMOTE_ROOT/web-releases' '$REMOTE_ROOT/incoming'"
scp "$archive" "$DEPLOY_HOST:$REMOTE_ROOT/incoming/$release_id.tar.gz"
ssh "$DEPLOY_HOST" "set -eu
  release='$REMOTE_ROOT/web-releases/$release_id'
  test ! -e \"\$release\"
  mkdir -p \"\$release\"
  tar -xzf '$REMOTE_ROOT/incoming/$release_id.tar.gz' -C \"\$release\"
  rm -f '$REMOTE_ROOT/incoming/$release_id.tar.gz'
  chmod 750 \"\$release/remote-deploy-web.sh\"
  APP_ROOT='$REMOTE_ROOT' \"\$release/remote-deploy-web.sh\" \"\$release\""

curl --fail --silent --show-error --max-time 15 https://songtop.xyz/plan/ >/dev/null
log "web deployment complete: https://songtop.xyz/plan/ ($release_id)"
