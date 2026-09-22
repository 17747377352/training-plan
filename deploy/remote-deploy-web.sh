#!/usr/bin/env bash
set -Eeuo pipefail

APP_ROOT="${APP_ROOT:-/opt/training-plan}"
RELEASE_DIR="${1:-}"
WEB_ROOT="${WEB_ROOT:-/var/www/plan}"

log() { printf '[remote-web] %s\n' "$*"; }
fail() { printf '[remote-web] ERROR: %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || fail "must run as root"
[[ -n "$RELEASE_DIR" ]] || fail "usage: remote-deploy-web.sh RELEASE_DIR"
RELEASE_DIR="$(readlink -f "$RELEASE_DIR")"
case "$RELEASE_DIR" in
  "$APP_ROOT"/web-releases/*) ;;
  *) fail "release directory is outside $APP_ROOT/web-releases" ;;
esac
[[ -f "$RELEASE_DIR/web/index.html" ]] || fail "web release is incomplete"

previous_web=""
[[ -L "$WEB_ROOT" ]] && previous_web="$(readlink -f "$WEB_ROOT")"
[[ -n "$previous_web" ]] || fail "$WEB_ROOT is not a managed symlink"

rollback() {
  ln -sfn "$previous_web" "$WEB_ROOT.rollback"
  mv -Tf "$WEB_ROOT.rollback" "$WEB_ROOT"
}
trap 'rollback; fail "web deployment interrupted and rolled back"' HUP INT TERM

ln -sfn "$RELEASE_DIR/web" "$WEB_ROOT.next"
mv -Tf "$WEB_ROOT.next" "$WEB_ROOT"
if ! curl --fail --silent --show-error --connect-timeout 3 --max-time 10 \
  https://songtop.xyz/plan/ >/dev/null; then
  rollback
  fail "public web check failed; previous web release restored"
fi
ln -sfn "$RELEASE_DIR" "$APP_ROOT/current-web.next"
mv -Tf "$APP_ROOT/current-web.next" "$APP_ROOT/current-web"
trap - HUP INT TERM
log "deployment succeeded: $(basename "$RELEASE_DIR")"
