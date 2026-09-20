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

随后只在本机的 `dev` 文件中填写数据库、Redis 和 Garmin 凭据。提交前可通过 `git status --ignored` 确认这些文件处于忽略状态。

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

## 验证认证链路

后端以 dev profile 运行且本地 MySQL、Redis 可用时，可执行端到端断言脚本：

```bash
bash server/scripts/verify-auth-e2e.sh
```

脚本覆盖注册、登录、令牌类型隔离、刷新轮换、并发双花、退出撤销、管理员接口授权边界和未认证访问，共 30 项断言，全部通过时退出码为 0。它会向本地开发库写入 `e2e` 前缀的测试用户。

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
