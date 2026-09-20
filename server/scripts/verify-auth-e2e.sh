#!/usr/bin/env bash
#
# 认证链路端到端验证脚本。
#
# 覆盖注册、登录、令牌类型隔离、刷新轮换、退出撤销和未认证访问，
# 重点断言 refresh token 不能作为 access token 访问业务接口。
#
# 前置条件：
#   1. 本地 MySQL 与 Redis 已启动（见 README 的本地依赖）。
#   2. 后端已以 dev profile 运行。
#
# 用法：
#   bash server/scripts/verify-auth-e2e.sh
#   BASE_URL=http://localhost:8080 bash server/scripts/verify-auth-e2e.sh
#
set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
RUN_ID="$(date +%s)"
USERNAME="e2e${RUN_ID}"
EMAIL="e2e${RUN_ID}@example.com"
PASSWORD="e2e-password-123"

PASSED=0
FAILED=0

green() { printf '\033[32m%s\033[0m\n' "$1"; }
red() { printf '\033[31m%s\033[0m\n' "$1"; }

pass() {
    PASSED=$((PASSED + 1))
    green "  PASS  $1"
}

fail() {
    FAILED=$((FAILED + 1))
    red "  FAIL  $1"
    [ -n "${2:-}" ] && printf '        %s\n' "$2"
}

# 发起请求并把响应体与状态码分别写入全局变量 BODY 和 STATUS。
request() {
    local method="$1" path="$2" data="${3:-}" token="${4:-}"
    local args=(-s -X "$method" "$BASE_URL$path" -H 'Content-Type: application/json')
    if [ -n "$token" ]; then
        args+=(-H "Authorization: Bearer $token")
    fi
    if [ -n "$data" ]; then
        args+=(-d "$data")
    fi
    local response
    response="$(curl "${args[@]}" -w $'\n%{http_code}')"
    STATUS="${response##*$'\n'}"
    BODY="${response%$'\n'*}"
}

assert_eq() {
    local label="$1" expected="$2" actual="$3"
    if [ "$expected" = "$actual" ]; then
        pass "$label"
    else
        fail "$label" "期望 [$expected]，实际 [$actual]"
    fi
}

echo "端到端验证目标：$BASE_URL"
echo

echo "[1] 健康检查"
request GET /api/system/health
assert_eq "健康接口返回 200" "200" "$STATUS"
assert_eq "健康接口业务码为 200" "200" "$(jq -r '.code' <<<"$BODY")"

echo
echo "[2] 注册"
request POST /api/auth/register \
    "{\"username\":\"$USERNAME\",\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}"
assert_eq "注册成功返回 200" "200" "$STATUS"
assert_eq "注册返回用户名" "$USERNAME" "$(jq -r '.data.username' <<<"$BODY")"
assert_eq "注册响应不含密码字段" "null" "$(jq -r '.data.passwordHash // .data.password // "null"' <<<"$BODY")"

request POST /api/auth/register \
    "{\"username\":\"$USERNAME\",\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}"
assert_eq "重复注册返回业务码 41001" "41001" "$(jq -r '.code' <<<"$BODY")"

echo
echo "[3] 登录"
request POST /api/auth/login "{\"account\":\"$USERNAME\",\"password\":\"wrong-password\"}"
assert_eq "错误密码返回业务码 41002" "41002" "$(jq -r '.code' <<<"$BODY")"

request POST /api/auth/login "{\"account\":\"$USERNAME\",\"password\":\"$PASSWORD\"}"
assert_eq "登录成功返回 200" "200" "$STATUS"
ACCESS_TOKEN="$(jq -r '.data.accessToken' <<<"$BODY")"
REFRESH_TOKEN="$(jq -r '.data.refreshToken' <<<"$BODY")"
[ -n "$ACCESS_TOKEN" ] && [ "$ACCESS_TOKEN" != "null" ] && pass "登录返回 access token" || fail "登录返回 access token"
[ -n "$REFRESH_TOKEN" ] && [ "$REFRESH_TOKEN" != "null" ] && pass "登录返回 refresh token" || fail "登录返回 refresh token"

echo
echo "[4] 资源访问与令牌类型隔离"
request GET /api/users/me
assert_eq "未携带令牌返回 401" "401" "$STATUS"
assert_eq "未认证业务码为 40100" "40100" "$(jq -r '.code' <<<"$BODY")"

request GET /api/users/me "" "$ACCESS_TOKEN"
assert_eq "access token 可访问当前用户接口" "200" "$STATUS"
assert_eq "当前用户接口返回本人" "$USERNAME" "$(jq -r '.data.username' <<<"$BODY")"

request GET /api/users/me "" "$REFRESH_TOKEN"
assert_eq "refresh token 不能访问业务接口（401）" "401" "$STATUS"
assert_eq "refresh token 访问被拒业务码为 40100" "40100" "$(jq -r '.code' <<<"$BODY")"

request GET /api/users/me "" "not-a-real-token"
assert_eq "伪造令牌返回 401" "401" "$STATUS"

echo
echo "[5] 刷新令牌一次性轮换"
request POST /api/auth/refresh "{\"refreshToken\":\"$REFRESH_TOKEN\"}"
assert_eq "使用 refresh token 换发成功" "200" "$STATUS"
ROTATED_ACCESS="$(jq -r '.data.accessToken' <<<"$BODY")"
ROTATED_REFRESH="$(jq -r '.data.refreshToken' <<<"$BODY")"
if [ -n "$ROTATED_REFRESH" ] && [ "$ROTATED_REFRESH" != "$REFRESH_TOKEN" ]; then
    pass "轮换后 refresh token 已更换"
else
    fail "轮换后 refresh token 已更换" "新旧 refresh token 相同"
fi

request POST /api/auth/refresh "{\"refreshToken\":\"$REFRESH_TOKEN\"}"
assert_eq "旧 refresh token 重放被拒（41004）" "41004" "$(jq -r '.code' <<<"$BODY")"

request GET /api/users/me "" "$ROTATED_ACCESS"
assert_eq "轮换后的 access token 可正常访问" "200" "$STATUS"

request GET /api/users/me "" "$ROTATED_REFRESH"
assert_eq "轮换后的 refresh token 仍不能访问业务接口" "401" "$STATUS"

echo
echo "[6] 退出与撤销"
request POST /api/auth/logout "{\"refreshToken\":\"$ROTATED_REFRESH\"}"
assert_eq "退出接口返回 200" "200" "$STATUS"

request POST /api/auth/refresh "{\"refreshToken\":\"$ROTATED_REFRESH\"}"
assert_eq "已撤销的 refresh token 无法再换发（41004）" "41004" "$(jq -r '.code' <<<"$BODY")"

echo
echo "[7] 并发双花（验证刷新令牌为原子消费）"
request POST /api/auth/login "{\"account\":\"$USERNAME\",\"password\":\"$PASSWORD\"}"
CONCURRENT_REFRESH="$(jq -r '.data.refreshToken' <<<"$BODY")"
CONCURRENT_ONE="$(mktemp)"
CONCURRENT_TWO="$(mktemp)"
curl -s -X POST "$BASE_URL/api/auth/refresh" -H 'Content-Type: application/json' \
    -d "{\"refreshToken\":\"$CONCURRENT_REFRESH\"}" >"$CONCURRENT_ONE" &
curl -s -X POST "$BASE_URL/api/auth/refresh" -H 'Content-Type: application/json' \
    -d "{\"refreshToken\":\"$CONCURRENT_REFRESH\"}" >"$CONCURRENT_TWO" &
wait
CONCURRENT_CODES="$(
    { jq -r '.code' <"$CONCURRENT_ONE"; jq -r '.code' <"$CONCURRENT_TWO"; }
)"
rm -f "$CONCURRENT_ONE" "$CONCURRENT_TWO"
assert_eq "并发使用同一 refresh token 只有一次换发成功" "1" "$(grep -c '^200$' <<<"$CONCURRENT_CODES")"
assert_eq "并发的另一次请求被拒绝（41004）" "1" "$(grep -c '^41004$' <<<"$CONCURRENT_CODES")"

echo
echo "--------------------------------------------"
if [ "$FAILED" -eq 0 ]; then
    green "全部通过：$PASSED 项断言"
    exit 0
fi
red "失败 $FAILED 项，通过 $PASSED 项"
exit 1
