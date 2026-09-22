# 导入 Garmin 令牌说明

给**使用平台的人**看的一页说明：怎么在自己电脑上取得 Garmin 令牌，再交给平台完成绑定。

## 为什么不能直接填账号密码

平台的服务器只有一个出口 IP，所有用户共用。Garmin 对登录端点按 IP 限流，还可能弹出
Cloudflare 人机挑战，在服务器上直接登录容易失败，而且一个人失败会连带其他人一起被限流。

改成**在你自己电脑上登录**，走的是你熟悉的网络，成功率高得多，而且**密码始终不出本机** ——
只有登录产生的令牌会交给平台。

## 准备工作

- 一台能上网的电脑（Windows / macOS / Linux 都行）
- 已安装 Python 3.10 或更高版本（终端执行 `python3 --version` 能看到版本号即可）
- 你的 Garmin 账号和密码

## 步骤

### 1. 安装依赖

```bash
pip install garminconnect cloudscraper
```

`cloudscraper` 用来自动通过 Cloudflare 挑战，建议装上（平台服务器端也是这么做的）；
没装也能跑，只是遇到人机挑战时更容易失败。

### 2. 运行脚本

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

### 3. 拿到令牌

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

### 4. 在平台上导入

打开平台的「Garmin 账号」页 → 点「导入令牌」，填三项：

- **邮箱**：刚才登录 Garmin 用的那个邮箱
- **站点**：与脚本一致（国际站 / 中国区）
- **令牌内容**：把上面那一整段 JSON 粘进去（连同花括号）

平台会先校验令牌是否真的可用，再加密存储。校验通过后账号出现在「已绑定账号」里，
就可以触发同步了。

## 常见问题

**提示「邮箱或密码不正确」**
密码确实错了，或者两步验证码输错了。重新运行脚本再试。

**提示「Garmin 正在限流」或「网络被 Garmin 拦住」**
多为 IP 被限流或 Cloudflare 人机挑战。处理办法：等 15~30 分钟再试；换一个网络
（手机热点常常有效）；确认已经 `pip install cloudscraper`。

**平台提示「Garmin 令牌已失效」**
令牌过期或被 Garmin 作废。重新跑一次本脚本，把新令牌再导入一次即可。

**一个令牌能给几个人用？**
不能。令牌是**某一个 Garmin 账号**的凭据，只代表那个账号。别人要用平台，需要用自己的
Garmin 账号跑一遍本脚本、导入自己的令牌。同一个 Garmin 账号也不能在同一个人名下绑两次。

**令牌会一直有效吗？**
平台在同步时会用令牌续期。目前实测一份两天前取得的令牌仍然可用；万一哪天失效，
按上面的办法重新导入即可，不需要重新绑定账号。

## 安全提醒

- 令牌等同于账号密码。只粘贴到平台自己的页面上，**不要发到群里、不要提交进任何仓库**。
- 平台用 AES-GCM 加密后存储，页面不会保留你粘贴的内容。
- 导入完成后可以删掉本地的 `garmin_token.json`。

## 维护者备注

- 脚本：`tools/garmin_token.py`。与采集器 `collector/src/training_plan_collector/garmin_auth.py`
  使用同一套调用顺序（先替换 `requests.Session` 为 cloudscraper 会话、再跳过三组慢速
  curl_cffi 登录策略），令牌格式即库的 `client.client.dumps()` 输出。
- 平台侧：`POST /api/garmin/accounts/import-token`（`ImportTokenRequest`：email / tokenJson /
  region，region 只接受 `GLOBAL` 或 `CN`）→ 采集器 `/internal/garmin/verify-token` 校验
  （走 `restore_session`，即 `client.login(tokenstore=...)`）→ 校验通过才 AES-GCM 加密入库。
- 唯一性：`garmin_account` 的唯一键是 `(user_id, garmin_email_hash)`，所以同一用户同一邮箱
  只能绑一次；但**平台不禁止两个平台用户绑定同一个 Garmin 邮箱**，目前也没有数量上限。
- 尚未验证：同步过程中库内部刷新出的新令牌不会回写数据库，库里存的始终是导入时那份快照。
  只要 Garmin 的 refresh token 不轮换就能一直用，但没有实测结论，遇到「令牌失效」按上面
  重新导入即可。
