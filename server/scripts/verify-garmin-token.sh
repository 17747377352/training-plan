#!/usr/bin/env bash
#
# Garmin 令牌恢复链路验证（不需要真实 Garmin 账号）。
#
# 做法：用应用自己的密钥，在库里写入一条带"格式正确但会被 Garmin 拒绝"的令牌的账号，
# 然后调用校验接口，断言：
#   1. 平台能正确解密该令牌（说明 AES-GCM 与 AAD 绑定规则一致）；
#   2. Collector 能用该令牌恢复会话并被 Garmin 拒绝；
#   3. 账号状态被置为 REAUTH_REQUIRED；
#   4. 校验接口返回需要重新认证的业务码。
#
# 前置条件：
#   1. 本地 MySQL 与 Redis 已启动，后端以 dev profile 运行；
#   2. Collector 已启动（uv run training-plan-collector）；
#   3. 传入一个已登录用户的凭据用于获取 access token。
#
# 用法：
#   ADMIN_ACCOUNT=<用户名> ADMIN_PASSWORD=<密码> bash server/scripts/verify-garmin-token.sh
#
set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_ACCOUNT="${ADMIN_ACCOUNT:-}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

if [ -z "$ADMIN_ACCOUNT" ] || [ -z "$ADMIN_PASSWORD" ]; then
    echo "请通过 ADMIN_ACCOUNT 与 ADMIN_PASSWORD 提供已登录用户的凭据" >&2
    exit 2
fi

PASSED=0
FAILED=0
ACCOUNT_ID=""

green() { printf '\033[32m%s\033[0m\n' "$1"; }
red() { printf '\033[31m%s\033[0m\n' "$1"; }
pass() { PASSED=$((PASSED + 1)); green "  PASS  $1"; }
fail() { FAILED=$((FAILED + 1)); red "  FAIL  $1"; [ -n "${2:-}" ] && printf '        %s\n' "$2"; }
assert_eq() {
    if [ "$2" = "$3" ]; then pass "$1"; else fail "$1" "期望 [$2]，实际 [$3]"; fi
}

cleanup() {
    if [ -n "$ACCOUNT_ID" ]; then
        cd "$REPO_ROOT" && uv run --quiet --with pymysql --with pyyaml python - "$ACCOUNT_ID" <<'PY' >/dev/null 2>&1 || true
import pathlib, pymysql, sys, yaml
cfg = yaml.safe_load(pathlib.Path("server/src/main/resources/application-dev.yml").read_text(encoding="utf-8"))
ds = cfg["spring"]["datasource"]; url = ds["url"]
hp, _, dbq = url.split("//", 1)[1].partition("/"); host, _, port = hp.partition(":"); db = dbq.split("?")[0]
conn = pymysql.connect(host=host, port=int(port), user=ds["username"], password=ds["password"], database=db, autocommit=True)
conn.cursor().execute("DELETE FROM garmin_account WHERE id=%s", (sys.argv[1],))
conn.close()
PY
    fi
}
trap cleanup EXIT

cd "$REPO_ROOT"

echo "写入一条带伪造令牌的 Garmin 账号记录"
ACCOUNT_ID="$(
uv run --quiet --with pymysql --with pyyaml --with cryptography python - "$ADMIN_ACCOUNT" <<'PY'
import base64, json, pathlib, pymysql, sys, yaml
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

cfg = yaml.safe_load(pathlib.Path("server/src/main/resources/application-dev.yml").read_text(encoding="utf-8"))
ds = cfg["spring"]["datasource"]; url = ds["url"]
hp, _, dbq = url.split("//", 1)[1].partition("/"); host, _, port = hp.partition(":"); db = dbq.split("?")[0]
key = base64.b64decode(cfg["app"]["security"]["token-cipher-key"])

conn = pymysql.connect(host=host, port=int(port), user=ds["username"], password=ds["password"], database=db, autocommit=True)
cur = conn.cursor()
cur.execute("SELECT id FROM sys_user WHERE username=%s", (sys.argv[1],))
row = cur.fetchone()
if row is None:
    print("", end=""); raise SystemExit(0)
user_id = row[0]
cur.execute("INSERT INTO garmin_account (user_id, region, garmin_email_hash, garmin_email_masked, auth_status, sync_enabled)"
            " VALUES (%s,'CN',%s,'probe***@example.com','ACTIVE',1)", (user_id, "0" * 64))
account_id = cur.lastrowid
token_json = json.dumps({"di_token": "forged", "di_refresh_token": "forged", "di_client_id": "forged"})
iv = b"\x01" * 12
blob = iv + AESGCM(key).encrypt(iv, token_json.encode(), str(account_id).encode())
cur.execute("UPDATE garmin_account SET token_ciphertext=%s WHERE id=%s",
            (base64.b64encode(blob).decode(), account_id))
conn.close()
print(account_id, end="")
PY
)"

if [ -z "$ACCOUNT_ID" ]; then
    red "无法写入探测账号：请确认 ADMIN_ACCOUNT 对应的用户存在" >&2
    exit 2
fi
echo "探测账号 id=$ACCOUNT_ID"
echo

TOKEN="$(curl -s -X POST "$BASE_URL/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"account\":\"$ADMIN_ACCOUNT\",\"password\":\"$ADMIN_PASSWORD\"}" | jq -r '.data.accessToken')"
if [ -z "$TOKEN" ] || [ "$TOKEN" = "null" ]; then
    red "登录失败，无法获取 access token" >&2
    exit 2
fi

echo "[1] 校验令牌"
VERIFY_BODY="$(curl -s -m 90 -X POST "$BASE_URL/api/garmin/accounts/$ACCOUNT_ID/verify" \
    -H "Authorization: Bearer $TOKEN")"
VERIFY_CODE="$(jq -r '.code' <<<"$VERIFY_BODY")"
echo "     响应: $(jq -c '.' <<<"$VERIFY_BODY")"
assert_eq "伪造令牌被判定为需要重新认证（46001）" "46001" "$VERIFY_CODE"

echo
echo "[2] 账号状态已落库"
STATUS="$(curl -s "$BASE_URL/api/garmin/accounts" -H "Authorization: Bearer $TOKEN" \
    | jq -r ".data[] | select(.id == $ACCOUNT_ID) | .authStatus")"
assert_eq "状态变为 REAUTH_REQUIRED" "REAUTH_REQUIRED" "$STATUS"

echo
echo "[3] 清理"
curl -s -o /dev/null -X DELETE "$BASE_URL/api/garmin/accounts/$ACCOUNT_ID" -H "Authorization: Bearer $TOKEN"
REMAIN="$(curl -s "$BASE_URL/api/garmin/accounts" -H "Authorization: Bearer $TOKEN" \
    | jq -r "[.data[] | select(.id == $ACCOUNT_ID)] | length")"
assert_eq "探测账号已删除" "0" "$REMAIN"
ACCOUNT_ID=""

echo
echo "--------------------------------------------"
if [ "$FAILED" -eq 0 ]; then
    green "全部通过：$PASSED 项断言"
    exit 0
fi
red "失败 $FAILED 项，通过 $PASSED 项"
exit 1
