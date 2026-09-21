# Training Plan

面向个人开发者和小规模多用户场景的 Garmin 数据管理平台。Java、Vue 和 Python 三个项目统一维护在本仓库。

## 项目结构

```text
training-plan/
├── server/       # JDK 17 + Spring Boot 3.5 + MyBatis-Plus
├── web/          # Vue 3 + TypeScript + Vite
├── collector/    # Python 3.12 + python-garminconnect
├── docs/         # 设计和开发进度
└── storage/      # 本地运行数据，不提交 Git
```

## 本地依赖

- JDK 17
- Maven 3.6.3+
- Node.js 20.19+ 或 22.12+
- Python 3.12，由 `uv` 管理
- MySQL 8，默认地址 `127.0.0.1:13306`
- Redis 7，默认地址 `127.0.0.1:6379`

## 本地配置

真实开发配置不提交 Git。首次运行时分别复制示例文件：

```bash
cp server/src/main/resources/application-dev.example.yml server/src/main/resources/application-dev.yml
cp web/.env.example web/.env.development
cp collector/.env.example collector/.env.dev
```

随后只在本机的 `dev` 文件中填写真实值。需要自己生成的三个值：

| 配置项 | 生成方式 | 说明 |
|---|---|---|
| `app.security.jwt-secret` | 至少 32 个字符的随机串 | 签发平台令牌 |
| `app.security.token-cipher-key` | `openssl rand -base64 32` | 加密 Garmin Token，**丢失后所有 Garmin 账号需重新认证** |
| `app.collector.service-token` | `openssl rand -base64 32` | 服务端与采集器之间的内部凭据，需与 `collector/.env.dev` 的 `COLLECTOR_SERVER_TOKEN` 一致 |

提交前可通过 `git status --ignored` 确认这些文件处于忽略状态。

## 启动后端

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
cd server
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

健康检查：`GET http://localhost:8080/api/system/health`

开发环境的 JWT 密钥需在 `application-dev.yml` 的 `app.security.jwt-secret` 中配置，长度至少 32 个字符。

## 启动前端

```bash
cd web
npm install
npm run dev
```

打开 `http://localhost:5173/register` 创建平台用户。登录后前端会携带 access token，并在过期时使用 Redis 中的一次性 refresh token 自动续期。

## 启动采集器

```bash
cd collector
uv sync
uv run training-plan-collector
```

采集器监听 `127.0.0.1:8090`，只提供两个内网接口，均要求请求头携带 `X-Collector-Token`（与服务端 `app.collector.service-token` 保持一致）：

- `GET /health`：健康检查，无需凭据。
- `POST /internal/garmin/connect`、`POST /internal/garmin/connect/mfa`：Garmin 登录与 MFA，仅由服务端调用。

采集器不对外暴露，启动时绑定回环地址。**MFA 会话保存在采集器进程内存中，因此首期必须单副本运行**，否则提交验证码的请求可能落到没有该会话的副本。

Garmin 会对登录端点做 IP 级限流。一旦登录被限流或遇到 Cloudflare 人机挑战，采集器会进入冷却期（`COLLECTOR_RATE_LIMIT_COOLDOWN_SECONDS`，默认 900 秒），期间不再发起任何 Garmin 请求，接口直接返回剩余等待时间。**不要在此期间反复重试，重复尝试会延长限流。** 浏览器能正常登录 Garmin 官网并不代表程序能登录：程序必须走未认证的 SSO 端点，而浏览器可以复用会话并通过人机挑战。

### Cloudflare 挑战绕过

采集器在构造认证服务时会把库内部的 `requests.Session` 替换为 `cloudscraper` 会话，用于通过 Garmin SSO 上的 Cloudflare 挑战。未启用时实测 5 段登录策略全部失败（4 段 429、1 段 403 人机挑战）；启用后可用 8 秒完成登录。细节见 `collector/src/training_plan_collector/garmin_http.py`。

删除令牌路径**不受冷却期影响**：令牌恢复只访问 API 层（实测 1 秒），与 SSO 限流无关。

设置 `COLLECTOR_DISABLE_CLOUDFLARE_BYPASS=1` 可关闭该绕过，用于定位问题是否由它引入。注意 cloudscraper 只能处理经典的 IUAM JS 挑战，Garmin 若改用 CAPTCHA 或托管挑战则会失效——实测已遇到过一次 `CAPTCHA required (bot challenge)`。

## Garmin 账号接口

以下接口都只作用于当前登录用户自己的账号：

- `GET /api/garmin/accounts`：已绑定账号列表，不返回任何令牌字段。
- `POST /api/garmin/accounts/connect`：提交 Garmin 邮箱、密码与区域（`GLOBAL` 或 `CN`）。
- `POST /api/garmin/accounts/connect/mfa`：提交验证码完成连接。
- `POST /api/garmin/accounts/import-token`：导入已有令牌完成绑定，用于程序登录被 Garmin 拦下时。
- `POST /api/garmin/accounts/{id}/verify`：用已存令牌校验账号是否仍可用，失效时状态置为 `REAUTH_REQUIRED`。
- `POST /api/garmin/accounts/{id}/sync`：触发一次同步，请求体 `{"days": 7}` 控制回溯天数。
- `PUT /api/garmin/accounts/{id}/auto-sync`：启用或暂停自动同步。
- `DELETE /api/garmin/accounts/{id}`：删除账号绑定。

`connect` 返回 `status` 为 `CONNECTED` 或 `MFA_REQUIRED`；后者需带上 `loginSessionId` 调用 MFA 接口。Garmin 密码只在请求期间使用，不写数据库也不写日志。令牌使用 AES-GCM 加密后存入 `garmin_account.token_ciphertext`，密钥来自 `app.security.token-cipher-key`。

网页端入口：登录后点击首页的「Garmin 账号」，或在 `/garmin` 直接打开，可在页面上完成连接、输入 MFA 验证码、校验令牌、暂停同步与删除绑定。

### 令牌导入

程序登录被 Garmin 限流或人机验证拦住时，用页面上的「导入令牌」：在浏览器登录 `connect.garmin.com`，取得 `{"di_token":..,"di_refresh_token":..,"di_client_id":..}` 后连同 Garmin 邮箱与站点一起提交。平台会先向采集器校验令牌可用，再使用 AES-GCM 加密存储；令牌约一年有效且由库自动刷新，无需重复登录。

令牌等同账号凭据，**不要提交到 Git**。本地留存的令牌建议放在已被 `.gitignore` 忽略的 `storage/` 目录下。

## 数据同步

触发同步后，平台创建 `sync_job` 并投入 Redis 队列（`app.sync.task-queue`，需与采集器 `COLLECTOR_TASK_QUEUE` 一致）；采集器取出任务后按任务 ID 通过 `/internal/collector/jobs/{id}/session` 换取解密后的令牌（**队列载荷本身不含令牌**），拉取数据再回传落库。

当前同步四类数据：**每日健康**（步数、距离、静息心率、压力、身体电量）、**睡眠**（时长、分期、评分、睡眠 HRV）、**HRV**（夜间值、7 日均值、状态、个人基线区间）、**骑行活动**（距离、爬升、AP/NP/TSS/IF、左右平衡、Z1–Z7 功率区间、踏频）。

入库约定：缺失指标存 `NULL` 而不是 0；睡眠按入睡时间去重，同一天的午睡不会被合并；时间统一存 GMT；活动类型存 `type_key`（如 `road_biking`）而非中文显示名。活动表**不存 GPS 坐标、位置名与账号姓名**。

同步有三种触发方式：

1. **首次绑定后弹窗询问**：可在页面上选择是否立即拉取最近 15 天历史数据，选择「暂不」不影响后续使用。
2. **每日定时同步**：每天 **09:00（Asia/Shanghai）** 自动为所有已连接且开启自动同步的账号拉取当日数据。
3. **手动触发**：账号列表的「同步」按钮拉取最近 7 天；接口可通过 `days` 指定回溯天数。

实测耗时：3 天约 5 秒，30 天约 40 秒，90 天约 90 秒（逐日拉取）。

定时任务参数：`app.sync.daily-cron`（默认 `0 0 9 * * *`）、`app.sync.daily-days`（默认 1）、`app.sync.zone`（默认 `Asia/Shanghai`）。已有未完成任务在飞的账号会被跳过，避免同一账号并发同步。

长时间无人推进的任务会被定时清理器收敛：停留在 `PENDING` 超过 `app.sync.pending-timeout`（默认 10 分钟）、或 `RUNNING` 超过 `app.sync.running-timeout`（默认 60 分钟）的任务会被置为 `FAILED` 并标记 `SYNC_JOB_TIMEOUT`。采集器进程消失或与平台断连时不会留下永久的假「进行中」记录。

## 验证认证链路

后端以 dev profile 运行且本地 MySQL、Redis 可用时，可执行端到端断言脚本：

```bash
bash server/scripts/verify-auth-e2e.sh
```

脚本覆盖注册、登录、令牌类型隔离、刷新轮换、并发双花、退出撤销、管理员接口授权边界和未认证访问，共 30 项断言，全部通过时退出码为 0。它会向本地开发库写入 `e2e` 前缀的测试用户。

Garmin 令牌恢复链路可以脱离真实账号验证（需要后端与采集器都在运行）：

```bash
ADMIN_ACCOUNT=<用户名> ADMIN_PASSWORD=<密码> bash server/scripts/verify-garmin-token.sh
```

脚本会用应用自己的密钥写入一条带伪造令牌的账号，断言平台能正确解密、采集器能恢复会话并被 Garmin 拒绝、账号状态落为 `REAUTH_REQUIRED`，最后自动清理该探测账号。

## 管理员接口

`/api/admin/**` 仅对 `ADMIN` 角色开放，普通用户访问返回 403：

- `GET /api/admin/users`：分页查询平台用户，支持 `page`、`size`、`keyword`、`status` 参数。
- `PUT /api/admin/users/{id}/status`：启用或禁用用户，`status` 取 0 或 1。

注册接口只会分配 `USER` 角色，平台不提供创建管理员的接口，首个管理员需在数据库中人工授予：

```sql
INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id FROM sys_user u, sys_role r
WHERE u.username = '<用户名>' AND r.role_code = 'ADMIN';
```

用户被禁用后无法登录、无法续期，但已签发的 access token 在最长 15 分钟内仍可通过签名校验。

## 开发状态

开发进度和下一步任务记录在 [docs/开发进度.md](docs/开发进度.md)。

当前已完成到**阶段三：健康数据同步**（每日健康、睡眠、HRV、骑行活动可自动同步入库）。尚未完成：数据查询接口与趋势看板、前端导航菜单、同步任务看板、数据导出。
