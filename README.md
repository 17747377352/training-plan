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
./mvnw spring-boot:run
```

健康检查：`GET http://localhost:8080/api/system/health`

## 启动前端

```bash
cd web
npm install
npm run dev
```

## 启动采集器

```bash
cd collector
uv sync
uv run training-plan-collector
```

## 开发状态

开发进度和下一步任务记录在 [docs/开发进度.md](docs/开发进度.md)。

