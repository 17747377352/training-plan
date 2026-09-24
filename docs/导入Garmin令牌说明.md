# 导入 Garmin 令牌说明

给**使用平台的人**看的一页说明：怎么在自己电脑上取得 Garmin 令牌，再交给平台完成绑定。

> **先确认你该走哪条路。** 本文讲的是**令牌路径**：令牌交给平台、由平台服务器去取数。
> 另有一条**本机浏览器上传**路径（`skills/garmin-browser-sync`）：Garmin 密码与会话完全留在本机，
> 平台不持有任何 Garmin 令牌，也不需要服务器能连上 Garmin；配对同样用「用桌面助手绑定」的
> 那个一次性配对码。取舍：
>
> | | 令牌路径（本文） | 本机浏览器上传（skill） |
> |---|---|---|
> | 服务器是否持有 Garmin 凭据 | 是（加密存令牌） | **否** |
> | 由谁取数 | 平台服务器 | 你自己的电脑 |
> | 需要本机常驻定时任务 | 不需要 | 需要（skill 的 `schedule`） |
> | 适合 | 只想绑定后不管 | 希望凭据不离开本机、且能接受本机定时 |
>
> 两者可以并存，但**同一账号不要同时用**：走本机浏览器上传会把该账号切成浏览器来源并清掉
> 服务器上的令牌，旧的服务端定时采集从此跳过它。

## 两种方式，推荐第一种

| 方式 | 你要做的事 | 说明 |
|---|---|---|
| **桌面助手（推荐）** | 运行助手 → 领取配对码 → 本机浏览器登录 Garmin | 助手会自己把令牌交回平台，**你不需要看到或复制令牌** |
| 手动导入 | 跑脚本取得令牌 → 复制那一整段 JSON → 粘到平台的「导入令牌」 | 已持有令牌或需要手动传递时使用（也需要 Python） |

下面是两种方式的详细步骤。

## 方式一：桌面助手

1. 打开平台的「Garmin 账号」页，点「用桌面助手绑定」。先完成下面的安装，再领取新的配对码（8 位、5 分钟有效、只能用一次）。
2. 下载助手：[`garmin_pair_helper.py`](../web/public/garmin_pair_helper.py)，或直接在浏览器里打开同名的下载链接
3. 在助手所在目录跑起来。**依赖装在独立环境里，不会动你的系统 Python**：

   macOS / Linux（需要 Python 3.12+）：

   ```bash
   python3 -m venv .venv
   .venv/bin/python -m pip install garminconnect==0.3.16 playwright
   .venv/bin/python -m playwright install chromium
   .venv/bin/python garmin_pair_helper.py --manual
   ```

   Windows（PowerShell / cmd）：

   ```bat
   python -m venv .venv
   .venv\Scripts\python -m pip install garminconnect==0.3.16 playwright
   .venv\Scripts\python -m playwright install chromium
   .venv\Scripts\python garmin_pair_helper.py --manual
   ```

   已经装了 [uv](https://docs.astral.sh/uv/) 可执行：

   ```bash
   uv run --python 3.12 --with playwright python -m playwright install chromium
   uv run --python 3.12 --with garminconnect==0.3.16 --with playwright python garmin_pair_helper.py --manual
   ```

   > **不要在 macOS 上直接 `pip install`**：Homebrew / 系统自带的 Python 有 PEP 668 保护，
   > 会报 `externally-managed-environment`。那不是缺东西，是系统不允许往全局环境装包，
   > 用上面的 venv 方式即可（助手本身在缺依赖时也会把这几条命令打印出来）。

4. 助手会打开一个 Garmin 官方登录窗口（没打开的话，看终端里打印的本机地址，手动访问它）
5. 在助手页面上填：配对码、Garmin 登录邮箱（**手动模式下不需要填密码**）
6. **在那个 Garmin 窗口里自己输入邮箱和密码并点登录**；出现人机验证、验证码也在这里完成
7. 看到「绑定成功」就可以了 —— 回到平台页面，账号已经出现

**为什么用 `--manual`（推荐）**：助手替你填表并点登录（`fill` + `click`）本身就是机器人特征，
实测会直接触发 Cloudflare 人机验证。手动模式下助手只打开官方页面，输入全部由你完成，
它退到后面等登录结果、再把票据换成平台能用的令牌 —— 那一步才是手动做不到的部分。
手动模式隐含 `--headed`（必须有可见窗口），等待上限也从 2 分钟放宽到 10 分钟。

助手只监听本机（`127.0.0.1`），每次运行都会换一个随机地址；**密码只输入到 Garmin 官方页面，既不发平台、也不经过助手**。

助手默认使用无头 Chromium，登录、验证码提交、令牌兑换都由浏览器网络栈发起，
不会自动退回 requests。浏览器能执行登录页 JavaScript，但不能保证解除 Cloudflare 风控或 IP 限流。

- 遇到 CAPTCHA / 403：用 `--manual`（或 `--headed`）启动，在 Garmin 窗口里自己完成人机验证。
- 想用已安装的 Chrome：加 `--browser-channel chrome`，无需下载 Chromium。
- 浏览器未安装：在运行助手的同一个环境执行 `python -m playwright install chromium`。
- 返回 429：本次停止请求，并在助手中冷却 15 分钟；不要连续重试。
- 登录成功但配对码过期：回平台重新领码，再在助手中提交。令牌在内存保留 5 分钟，
  无需再次登录；绑定成功或超时后清除，浏览器在取得令牌后关闭。
- 原 HTTP 登录仅供排障：安装 `cloudscraper` 并加 `--login-method http`。它仍可能遇到原来的 429/403。
- 登录失败需排查：默认将阶段、HTTP 状态、Garmin `responseStatus` 及票据是否存在写入
  `~/.training-plan/garmin-pair-helper.log`，同时输出到终端。可用 `--log-file PATH` 改路径。
  日志不记录邮箱、密码、验证码、Cookie、票据或令牌正文；未知响应的诊断摘要也会显示在错误页。
  HTTP 200 仅代表请求收到响应，不代表登录成功，需结合 Garmin 的业务状态判断。

`--no-browser` 只禁止自动打开助手表单，不会关闭登录用的 Chromium。
密码会通过 HTTPS 发给 Garmin，**不会发给平台**；不保存浏览器配置、HAR、trace 或截图。

## 方式二：手动导入令牌

适合助手跑不起来的情况：在你自己电脑上取得令牌 JSON，再粘贴到平台。

### 为什么不能直接填账号密码

平台的服务器只有一个出口 IP，所有用户共用。Garmin 对登录端点按 IP 限流，还可能弹出
Cloudflare 人机挑战，在服务器上直接登录容易失败，而且一个人失败会连带其他人一起被限流。

改成**在你自己电脑上登录**，走的是你熟悉的网络，成功率高得多，而且**密码只通过 HTTPS 发给 Garmin，不发给平台** ——
只有登录产生的令牌会交给平台。

### 准备工作

- 一台能上网的电脑（Windows / macOS / Linux 都行）
- 已安装 Python 3.12 或更高版本（终端执行 `python3 --version` 能看到版本号即可）
- 你的 Garmin 账号和密码

### 具体步骤

#### 1. 安装依赖

**不要直接 `pip install`** —— macOS 上 Homebrew / 系统自带的 Python 会以 PEP 668 拒绝
（报 `externally-managed-environment`），而往全局环境硬装会污染系统 Python。用临时环境：

```bash
python3 -m venv .venv
.venv/bin/pip install garminconnect cloudscraper
```

Windows 把第二行换成 `.venv\Scripts\pip install garminconnect cloudscraper`。

`cloudscraper` 是旧 HTTP 路径的可选辅助依赖，无法保证通过托管挑战或 CAPTCHA。
遇到 403/429 时优先使用上面的 Playwright 桌面助手。

#### 2. 运行脚本

拿到本仓库的 `tools/garmin_token.py`，在它所在目录执行：

```bash
python3 garmin_token.py          # 国际站账号（connect.garmin.com）
python3 garmin_token.py --cn     # 中国区账号（connect.garmin.cn）
```

脚本会依次询问：

| 提示 | 说明 |
|---|---|
| `Garmin 登录邮箱` | 你登录 Garmin 用的邮箱 |
| `Garmin 密码（不会回显…）` | 输入时不显示字符，也不会被保存到任何地方 |
| `Garmin 发来的验证码` | 只有开了两步验证才会问；没开就不会出现 |

**站点一定要选对**：绝大多数账号是国际站；只有用 connect.garmin.cn 登录的账号才加 `--cn`。
选错的话后面平台校验令牌会失败。

#### 3. 拿到令牌

成功时终端长这样：

```
登录成功，账号：你的昵称
令牌已保存到：/你的路径/garmin_token.json
（包含字段：di_client_id, di_refresh_token, di_token）

下一步：打开平台的「Garmin 账号」页 → 导入令牌
  邮箱：you@example.com
  站点：国际站
  令牌内容：复制下面这一整段（连同花括号）粘贴进去

{"di_token":"...","di_refresh_token":"...","di_client_id":"..."}
```

同时在当前目录生成 `garmin_token.json`（权限 600），从文件里复制更稳妥。

#### 4. 在平台上导入

打开平台的「Garmin 账号」页 → 点「导入令牌」，填三项：

- **邮箱**：刚才登录 Garmin 用的那个邮箱
- **站点**：与脚本一致（国际站 / 中国区）
- **令牌内容**：把上面那一整段 JSON 粘进去（连同花括号）

平台会先校验令牌是否真的可用，再加密存储。校验通过后账号出现在「已绑定账号」里，
就可以触发同步了。

### 常见问题

**提示「邮箱或密码不正确」**
密码确实错了，或者两步验证码输错了。重新运行脚本再试。

**提示「Garmin 正在限流」或「网络被 Garmin 拦住」**
多为 IP 被限流或 Cloudflare 人机挑战。处理办法：等 15~30 分钟再试；换一个网络
（手机热点常常有效）；确认 `cloudscraper` 已装好（见「安装依赖」，用 venv 装）。

**运行时报 `externally-managed-environment`**
这是 macOS 上 Homebrew / 系统 Python 的 PEP 668 保护，不是缺依赖。按「安装依赖」
改用 venv（或 `uv run --with ...`）即可，不要加 `--break-system-packages` 硬装。

**平台提示「Garmin 令牌已失效」**
令牌过期或被 Garmin 作废。重新跑一次本脚本，把新令牌再导入一次即可。

**一个令牌能给几个人用？**
不能。令牌是**某一个 Garmin 账号**的凭据，只代表那个账号。别人要用平台，需要用自己的
Garmin 账号跑一遍本脚本、导入自己的令牌。同一个 Garmin 账号也不能在同一个人名下绑两次。

**令牌会一直有效吗？**
平台在同步时会用令牌续期。目前实测一份两天前取得的令牌仍然可用；万一哪天失效，
按上面的办法重新导入即可，不需要重新绑定账号。

### 安全提醒

- 令牌等同于账号密码。只粘贴到平台自己的页面上，**不要发到群里、不要提交进任何仓库**。
- 平台用 AES-GCM 加密后存储，页面不会保留你粘贴的内容。
- 导入完成后可以删掉本地的 `garmin_token.json`。

## 维护者备注

- **助手（推荐路径）**：`web/public/garmin_pair_helper.py`，由 Vite 原样发布到
  `<base>/garmin_pair_helper.py`，前端用 `import.meta.env.BASE_URL` 拼下载地址。
  它只监听 `127.0.0.1`，每次运行生成随机路径前缀（`/<token>/`）作为访问凭据 ——
  本机 Web 服务如果只靠端口，任何网页都能通过 127.0.0.1 扫端口提交表单。
  默认使用 Playwright；固定 `garminconnect==0.3.16`，要求 Python 3.12+。
  仅导出上游支持的 `di_token` / `di_refresh_token` / `di_client_id`，不导出 JWT_WEB Cookie。
  本机 HTTP 服务并发接收请求，避免浏览器预连接阻塞；登录、MFA 与资源释放
  交给单个专用工作线程，确保 Playwright 始终由同一线程操作；
  闲置 MFA 会话和待交付令牌在 5 分钟后释放。
  支持 `--token-file` 直接交回已有令牌（不登录 Garmin），便于排障与自动化验证。
- 平台侧新增：`POST /api/garmin/accounts/pair-code`（需登录，签发一次性配对码）与
  `POST /api/garmin/accounts/pair`（**免登录**，凭配对码回传令牌）。配对码存 Redis
  （`garmin:pair:` 前缀、5 分钟、`getAndDelete` 取用即删），定位到的 userId 再走
  `importToken` 的同一套校验与加密入库。
  免登录是必须的（助手没有平台登录态），所以 `SecurityConfig` 里**只放行这一个精确路径**，
  `GarminPairSecurityTest` 会用「其余 `/api/garmin/accounts/**` 必须 401」把通配写法挡住。
  服务层刻意**先取码再校验令牌**：这个接口匿名可调，若先校验令牌，任何人都能拿它消耗
  采集器与 Garmin 的调用配额。
- 手动脚本：`tools/garmin_token.py`。与采集器 `collector/src/training_plan_collector/garmin_auth.py`
  使用同一套调用顺序（先替换 `requests.Session` 为 cloudscraper 会话、再跳过三组慢速
  curl_cffi 登录策略），令牌格式即库的 `client.client.dumps()` 输出。
- 平台侧手动路径：`POST /api/garmin/accounts/import-token`（`ImportTokenRequest`：email /
  tokenJson / region，region 只接受 `GLOBAL` 或 `CN`）→ 采集器
  `/internal/garmin/verify-token` 校验（走 `restore_session`，即
  `client.login(tokenstore=...)`）→ 校验通过才 AES-GCM 加密入库。
- 唯一性：`garmin_account` 的唯一键是 `(user_id, garmin_email_hash)`，所以同一用户同一邮箱
  只能绑一次；但**平台不禁止两个平台用户绑定同一个 Garmin 邮箱**，目前也没有数量上限。
- 尚未验证：同步过程中库内部刷新出的新令牌不会回写数据库，库里存的始终是导入时那份快照。
  只要 Garmin 的 refresh token 不轮换就能一直用，但没有实测结论，遇到「令牌失效」按上面
  重新导入即可。
