---
name: garmin-browser-sync
description: "在用户本机用反检测浏览器采集 Garmin 健康与骑行数据，配对后推送到训练计划平台，支持失败补传与无人值守定时。当用户要「同步 Garmin 数据到平台」「补齐某段时间的 Garmin 数据」「绑定/配对 Garmin 账号」，或定时任务需要拉取当日健康、睡眠、午睡、HRV、训练状态与骑行活动时使用。程序化登录已被 Garmin 封堵，必须走本机的浏览器采集。"
---

# Garmin 浏览器同步

把 Garmin 数据从**用户自己的电脑**采下来并推送进训练计划平台。

**为什么必须在本机做**：Garmin 自 2026 年 3 月起加强 Cloudflare 防护，程序化登录（garth、
`python-garminconnect` 的账号密码路径）基本失效；实测唯一可用的模式是**反检测浏览器取数**
（SeleniumBase UC 模式，且请求也从浏览器里发出）。同时 Garmin 登录端点按 IP 限流，服务器只有一个
出口 IP，多用户共用必然互相拖累，所以取数放在用户自己的网络里。

平台因此**不再持有 Garmin 令牌**：Garmin 密码与浏览器会话完全留在本机，平台只接收白名单业务数据。

## 何时使用

- 用户要把 Garmin 数据同步进平台，或补齐某段历史
- 首次绑定：领取配对码并把本机接上平台
- 定时任务：每天把最近几天数据推上去

## 何时不要使用

- 只想让 AI 读 Garmin 原始数据做分析 → 那是上游项目自己的 MCP 工具，不是本 skill
- 需要写入 Garmin（改活动、排课、上传路线）→ 本 skill 只读

## 工作流

命令都在 `scripts/` 下执行，状态默认写在 `storage/`（已被 gitignore）：

```bash
# 1. 一次性：装隔离环境（venv + 上游 garmin-givemydata==0.1.13，需 uv 或 Python 3.10+）
python3 garmin_sync.py prepare

# 2. 一次性：配对并配置。配对码来自平台「Garmin 账号」页的「用桌面助手绑定」
GARMIN_PAIR_CODE=<8位码> python3 garmin_sync.py setup \
    --server https://songtop.xyz/planapi --email you@example.com
#    只配对、暂不配置无人值守抓取：加 --without-garmin-password
#    复用已经登录成功的上游浏览器会话：加 --session-from <目录>

# 3. 日常：先补传缺口，再取最近三天并上传（需要时加 --visible 人工过验证）
python3 garmin_sync.py sync

# 4. 只补传：本地已有数据，不登录 Garmin，不消耗登录配额
python3 garmin_sync.py upload --since 2026-09-12 --until 2026-09-24
python3 garmin_sync.py upload --db <其它 garmin.db> --dry-run   # 只看会传多少条

# 5. 改 Garmin 密码 / 体检（体检不显示凭据）
GARMIN_PASSWORD=<新密码> python3 garmin_sync.py credentials
python3 garmin_sync.py doctor

# 6. 每日定时（macOS launchd）：默认每天 10:30 跑一次 sync
python3 garmin_sync.py schedule --print            # 先看生成的 plist，不写系统
python3 garmin_sync.py schedule --hour 7 --minute 20
python3 garmin_sync.py schedule --uninstall        # 卸载
```

无人值守要求先配好 Garmin 密码（`doctor` 的 `garminCredentialReady` 必须为 true）；
只有 `upload` 补传不需要密码。多套配置（例如测试环境与线上各一份）用 `--state-dir`
配 `--label` 各装一个任务。

`--state-dir` 可指定状态目录（默认在 skill 自己的 `storage/`）。所有命令输出单行 JSON，便于 agent
读取与汇报；失败时为 `{"ok": false, "error": ...}` 并以退出码 1 结束。

`sync` 的取数范围：默认「今天减 3 天」到「昨天」，并会向前多看两天已上传日期以补缺口。只上传
**完整日期**（当天不传，当天数据还在变）。`sync --since <日期>` 可指定更早的起点做回填。

## 绑定到线上环境

首次接线上平台：配对码只能在**已登录的平台页面**上生成，所以这一步要由本人操作。

```bash
# 1. 在 https://songtop.xyz/plan → Garmin 账号 → 用桌面助手绑定，取 8 位配对码（5 分钟有效）
# 2. 一条命令完成配对 + 保存密码 + 复用已登录会话（密码用隐藏输入，不必写进命令历史）
GARMIN_PAIR_CODE=<8位码> python3 garmin_sync.py setup \
    --server https://songtop.xyz/planapi --email <Garmin 国际站邮箱> \
    --session-from "<已有会话目录>"
# 3. 已有本地数据时先零登录回填，不要为了补齐历史去重新登录 Garmin
python3 garmin_sync.py upload --db "<已有 garmin.db>" --since 2026-09-12 --until 2026-09-24
# 4. 确认无误后再挂定时
python3 garmin_sync.py schedule --hour 10 --minute 30
```

`upload` 只读本地 SQLite，**不碰 Garmin 登录端点**，所以回填历史不消耗登录配额；
真正需要取新数据时才用 `sync`。生产账号若此前是用令牌导入的，配对会把它切成浏览器来源
（清掉服务器上的令牌），旧的服务端定时采集从此跳过该账号，这是设计如此。

## 关键设计

- **配对换凭据**：一次性配对码换取只绑定一个账号的上传凭据（`storage/config.json`，600 权限），
  之后长期复用；平台侧可撤销。平台地址强制 HTTPS（本机调试可用 localhost HTTP）。
- **只读且只带白名单**：上游取数与落盘都在调用边界过滤，`user_profile`、`activity_trackpoints`（GPS）
  等表直接不落盘；姓名、账号 ID、经纬度、位置名等键在写入前被剔除，活动名统一写成「骑行」。
  载荷里不含原始响应、GPS 或账号身份信息。
- **只同步骑行**：非骑行活动（跑步、游泳等）在映射阶段跳过，避免混进骑行处方。
- **缺数据就拒绝上报**：每日健康缺任何一天、骑行活动缺 ID 或 GMT 开始时间、午睡缺开始时间，
  都会直接报错而不是少传几条 —— 静默少传比失败更难发现。
- **断点补传**：上传按 **7 天一批**，每批确认入库后才推进检查点；失败会把剩余区间写进
  `storage/progress.json`，重跑 `upload` 即可续传，**不需要重新登录 Garmin**。配置与检查点原子写入，
  权限 600。
- **单进程独占**：`storage/sync.lock` 文件锁保证同一状态目录同时只有一个进程使用浏览器会话。
- **取数有上限**：浏览器取数超过 30 分钟会被终止并提示改用 `--visible`，不会无限挂住。
- **平台按唯一键去重**：重复上传是覆盖更新，不会产生重复数据。
- **定时任务自愈**：plist 里解释器走 `/usr/bin/env python3` 而不是写死绝对路径 —— Homebrew
  升级后 Cellar 里的版本目录会消失，写死会让任务在无人察觉的情况下再也跑不起来。日志写在
  `<state-dir>/logs/schedule.log`；`RunAtLoad` 为 false，改时间不会意外触发一次真实取数。

## 已知缺口（不要当成采集失败去修）

以下为 2026-09-24 在真实账号与真实数据库上实测的结论，逐字段依据见
`references/字段映射核对表.md`。

| 缺口 | 实测原因 | 影响 |
|---|---|---|
| `ftpHistory` 为空 | 上游没有 FTP 接口；`lactate_threshold` 只存阈值**心率**，没有功率 | 处方强度只能按心率换算，不能按功率百分比 |
| `loadAerobic*`/`loadAnaerobic*`（9 项）与 `balanceFeedbackPhrase` 为空 | `load_focus` 有建表但**既无端点也无 upsert**，属真无源 | 判灯引擎的「低强度有氧不足」诊断缺依据 |
| 功率区间（`powerZone1~7Seconds`）为空 | 活动详情只在调试产物里，上游不写库；映射已留前向兼容代码 | 间歇质量分析缺少功率分布 |
| `training_readiness`/`endurance_score`/`hill_score` 为空 | 上游**确实请求了**这些端点，本账号返回空（204 空内容 / 空数组），平台也无对应字段 | 不是接线缺口；换设备或账号开通后仍需先加平台字段 |
| `avgElevation`/`max20minPower`/`avgLeftBalance`/`trainingEffectLabel`/`strokes` 为空 | 上游 `activity` 表没有这些列 | 骑行细节分析少几项 |

## 失败模式与处置

- **需要人工验证**：用 `sync --visible` 打开窗口，在里面完成人机验证后重试；不要连续重登。
- **登录 429**：Garmin 按 IP 限流，**配额约 1~2 小时恢复**（实测 15:47–17:06 连续 429 后，17:44 即登录成功）。
  等一会儿再试，或换网络（手机热点）。取数过程中遇到 401/403/429 会立即终止，不会连续重试登录。
- **会话过期**：重跑 `sync`；无人值守需先用 `credentials` 配置密码（`doctor` 的
  `garminCredentialReady` 为 false 就说明没配）。
- **上传失败但已取数**：本地数据保留，直接 `upload` 补传，**不要重新登录 Garmin**。
- **平台返回业务失败（HTTP 200 且 code≠200）**：按失败处理并保留检查点，按返回的 message 处置。
  上传仅对 5xx/429 与网络错误做有限重试。
- **上游取数没取到新鲜数据**：不会推进检查点，直接报出缺失日期；先 `doctor` 再 `sync --visible`。
- **活动列表请求失败**：不会把「空活动列表」当成「没有活动」，避免静默丢骑行记录。

## 依赖与许可证（分发前需确认）

上游 `garmin-givemydata` 是 **AGPL-3.0-only**（`pyproject.toml` 的 `license` 与仓库 `LICENSE` 一致）。
本 skill 的事实边界：

- 本 skill 的脚本**没有复制上游代码**：`prepare` 只是用 pip 把 `garmin-givemydata==0.1.13`
  装进 skill 自己的私有 venv；`upstream_runner.py` 通过 import 与运行时包装调用它。
- 因此**运行**时会形成「本 skill + AGPL 组件」的组合；AGPL §13 的要求针对的是**通过网络提供服务**
  的场景。
- **分发前要决定**：不要把上游源码或整个 venv 一起打包进交付物（那会变成分发 AGPL 代码，
  需要随附许可证与对应源码）；让使用者按 `prepare` 自行从 PyPI 安装，义务边界最清楚。
  若将来把带此 skill 的服务开放给他人使用，需要单独确认 AGPL 合规方式。

本 skill 自身的代码与文档不继承 AGPL，可另行授权。

## 验证

```bash
python3 -m unittest discover -s tests
```

测试覆盖：真实睡眠形状与隐私字段剔除、UTC 时间换算（不受本机时区影响）、ACWR 两层嵌套
（`latestTrainingStatusData[设备ID]`）、VO2max 按运动类型取骑行（且与入库顺序无关）、午睡数组、
心率区间转置、设备 ID、定时 plist（不写死解释器路径、时间校验、能被 launchd 解析）、
HTTP 200 + 业务失败不算成功、断点补传、坏回执不推进检查点、并发运行被拒。
