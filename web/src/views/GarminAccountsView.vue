<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from "vue";
import { ElMessage, ElTabPane, ElTabs } from "element-plus";
import PageHeading from "../components/PageHeading.vue";
import {
  createPairCode,
  deleteAccount,
  importToken,
  listAccounts,
  triggerSync,
  updateAutoSync,
  verifyAccount,
} from "../api/garmin";
import type {
  GarminAccount,
  GarminAuthStatus,
  GarminRegion,
} from "../types/api";

const accounts = ref<GarminAccount[]>([]);
const loading = ref(false);
const errorMessage = ref("");
const verifyingId = ref<number>();
const switchingId = ref<number>();
const sensitiveDataConsent = ref(false);

const backfillDialogVisible = ref(false);
const backfillAccountId = ref<number>();
const backfillRunning = ref(false);
const syncingId = ref<number>();

const importDialogVisible = ref(false);
const importSubmitting = ref(false);
const importForm = reactive({
  email: "",
  tokenJson: "",
  region: "GLOBAL" as GarminRegion,
});

/** 桌面助手配对：助手持码回传令牌，页面轮询账号列表即可知道绑定完成。 */
const pairDialogVisible = ref(false);
const pairCode = ref("");
const pairSeconds = ref(0);
const pairLoading = ref(false);
let pairPollTimer: number | undefined;

/** 助手文件名与下载地址：它由前端静态目录原样发布，用户点链接即可保存。 */
const PAIR_HELPER_FILE = "garmin_pair_helper.py";
const PAIR_HELPER_URL = `${import.meta.env.BASE_URL}${PAIR_HELPER_FILE}`;
const helperDownloadUrl = new URL(PAIR_HELPER_URL, window.location.origin).href;
const helperServerUrl = new URL(
  import.meta.env.VITE_API_BASE_URL || "/",
  window.location.origin,
).href.replace(/\/$/, "");

/** 两套命令均从用户目录开始，下载地址和服务端地址跟随当前部署环境。 */
const pairCommands = {
  mac: `mkdir -p "$HOME/garmin-pair-helper" &&
cd "$HOME/garmin-pair-helper" &&
curl --fail --location --output garmin_pair_helper.py '${helperDownloadUrl}' &&
python3 -c "import sys; assert sys.version_info >= (3, 12), 'Python 3.12+ required'" &&
python3 -m venv .venv &&
.venv/bin/python -m pip install garminconnect==0.3.16 playwright &&
.venv/bin/python -m playwright install chromium &&
.venv/bin/python garmin_pair_helper.py --manual --server '${helperServerUrl}'`,
  // 直接调用虚拟环境解释器，无需激活脚本或更改 PowerShell 执行策略。
  windows: String.raw`$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path "$env:USERPROFILE\garmin-pair-helper" | Out-Null
Set-Location "$env:USERPROFILE\garmin-pair-helper"
Invoke-WebRequest -Uri '${helperDownloadUrl}' -OutFile garmin_pair_helper.py
py -3 -c "import sys; assert sys.version_info >= (3, 12), 'Python 3.12+ required'"
if ($LASTEXITCODE -ne 0) { throw '请先安装 Python 3.12 或更高版本' }
py -3 -m venv .venv
if ($LASTEXITCODE -ne 0) { throw '创建虚拟环境失败，请查看上方错误' }
.\.venv\Scripts\python.exe -m pip install garminconnect==0.3.16 playwright
if ($LASTEXITCODE -ne 0) { throw '安装依赖失败，请查看上方错误' }
.\.venv\Scripts\python.exe -m playwright install chromium
if ($LASTEXITCODE -ne 0) { throw '安装浏览器失败，请查看上方错误' }
.\.venv\Scripts\python.exe garmin_pair_helper.py --manual --server '${helperServerUrl}'`,
};

async function copyPairCommands(platform: keyof typeof pairCommands) {
  try {
    await navigator.clipboard.writeText(pairCommands[platform]);
    ElMessage.success("完整命令已复制");
  } catch {
    ElMessage.info("复制失败，请手动选中下方命令复制");
  }
}

/** 首次绑定后可选拉取的历史天数。 */
const INITIAL_BACKFILL_DAYS = 15;
/** 手动同步默认回溯天数，与开发计划的增量策略一致（覆盖 Garmin 延迟修正）。 */
const MANUAL_SYNC_DAYS = 7;

const STATUS_LABELS: Record<GarminAuthStatus, string> = {
  PENDING: "待认证",
  PENDING_MFA: "待输入验证码",
  ACTIVE: "已连接",
  REAUTH_REQUIRED: "需要重新认证",
};

const STATUS_TAG_TYPES: Record<
  GarminAuthStatus,
  "success" | "info" | "warning" | "danger"
> = {
  PENDING: "info",
  PENDING_MFA: "warning",
  ACTIVE: "success",
  REAUTH_REQUIRED: "danger",
};

const hasAccounts = computed(() => accounts.value.length > 0);

function statusLabel(status: GarminAuthStatus): string {
  return STATUS_LABELS[status] ?? status;
}

function statusTagType(status: GarminAuthStatus) {
  return STATUS_TAG_TYPES[status] ?? "info";
}

function regionLabel(region: GarminRegion): string {
  return region === "CN" ? "中国区" : "国际站";
}

function formatTime(value?: string | null): string {
  if (!value) return "--";
  return new Date(value).toLocaleString("zh-CN");
}

async function loadAccounts() {
  loading.value = true;
  errorMessage.value = "";
  try {
    accounts.value = await listAccounts();
  } catch {
    errorMessage.value = "读取 Garmin 账号失败，请确认后端服务已启动。";
  } finally {
    loading.value = false;
  }
}

/** 绑定成功后询问是否首次拉取历史数据。 */
function askInitialBackfill(accountId?: number) {
  if (!accountId) {
    void loadAccounts();
    return;
  }
  backfillAccountId.value = accountId;
  backfillDialogVisible.value = true;
}

async function handleBackfill(agree: boolean) {
  const accountId = backfillAccountId.value;
  if (!agree || !accountId) {
    backfillDialogVisible.value = false;
    ElMessage.info("已跳过首次拉取，之后每天 9:00 会自动同步当日数据");
    await loadAccounts();
    return;
  }
  backfillRunning.value = true;
  try {
    await triggerSync(accountId, INITIAL_BACKFILL_DAYS);
    backfillDialogVisible.value = false;
    ElMessage.success(
      `已提交首次拉取（最近 ${INITIAL_BACKFILL_DAYS} 天），同步完成后即可查看`,
    );
    await loadAccounts();
  } catch {
    // 错误提示由拦截器统一处理
  } finally {
    backfillRunning.value = false;
  }
}

async function handleSync(account: GarminAccount) {
  syncingId.value = account.id;
  try {
    await triggerSync(account.id, MANUAL_SYNC_DAYS);
    ElMessage.success(`已提交同步（最近 ${MANUAL_SYNC_DAYS} 天）`);
  } catch {
    // 错误提示由拦截器统一处理
  } finally {
    syncingId.value = undefined;
  }
}

async function handleImportToken() {
  if (importSubmitting.value) return;
  if (!importForm.email || !importForm.tokenJson) {
    ElMessage.warning("请填写 Garmin 邮箱与令牌内容");
    return;
  }
  if (!sensitiveDataConsent.value) {
    ElMessage.warning("请先单独同意处理健康与训练数据");
    return;
  }
  importSubmitting.value = true;
  try {
    const account = await importToken({
      email: importForm.email.trim(),
      tokenJson: importForm.tokenJson.trim(),
      region: importForm.region,
    });
    importDialogVisible.value = false;
    // 令牌是凭据，导入后立即从表单里清掉
    importForm.tokenJson = "";
    ElMessage.success("令牌导入成功，账号已连接");
    askInitialBackfill(account?.id);
  } catch {
    // 错误提示由拦截器统一处理
  } finally {
    importSubmitting.value = false;
  }
}

/** 领码并打开助手引导弹窗；绑定由助手在用户自己机器上完成。 */
async function openPairDialog() {
  if (!sensitiveDataConsent.value) {
    ElMessage.warning("请先单独同意处理健康与训练数据");
    return;
  }
  pairLoading.value = true;
  try {
    const result = await createPairCode();
    pairCode.value = result.code;
    pairSeconds.value = result.expiresInSeconds;
    pairDialogVisible.value = true;
    startPairPolling();
  } catch {
    // 错误提示由拦截器统一处理
  } finally {
    pairLoading.value = false;
  }
}

/**
 * 轮询账号列表，助手绑定成功后自动关窗并进入首次回溯。
 *
 * 用轮询而不是让用户手动刷新，是因为助手与页面之间没有其它通道；
 * 5 秒一次、只在弹窗打开时运行，代价可以忽略。
 */
function startPairPolling() {
  stopPairPolling();
  const before = accounts.value.length;
  pairPollTimer = window.setInterval(async () => {
    try {
      const list = await listAccounts();
      accounts.value = list;
      if (list.length > before) {
        stopPairPolling();
        pairDialogVisible.value = false;
        ElMessage.success("绑定成功");
        askInitialBackfill(list[0]?.id);
      }
    } catch {
      // 单次轮询失败忽略，下一轮继续
    }
  }, 5000);
}

function stopPairPolling() {
  if (pairPollTimer !== undefined) {
    window.clearInterval(pairPollTimer);
    pairPollTimer = undefined;
  }
}

async function copyPairCode() {
  try {
    await navigator.clipboard.writeText(pairCode.value);
    ElMessage.success("配对码已复制");
  } catch {
    // 非 HTTPS 或浏览器不允许时退化为手动选中
    ElMessage.info("复制失败，请手动选中配对码");
  }
}

onUnmounted(stopPairPolling);

async function handleVerify(account: GarminAccount) {
  verifyingId.value = account.id;
  try {
    await verifyAccount(account.id);
    ElMessage.success("令牌有效，账号可正常使用");
  } catch {
    // 令牌失效时后端已把状态置为需要重新认证
  } finally {
    verifyingId.value = undefined;
    await loadAccounts();
  }
}

async function handleAutoSyncChange(account: GarminAccount, value: number) {
  switchingId.value = account.id;
  try {
    await updateAutoSync(account.id, value);
    account.syncEnabled = value;
    ElMessage.success(value === 1 ? "已启用自动同步" : "已暂停自动同步");
  } catch {
    await loadAccounts();
  } finally {
    switchingId.value = undefined;
  }
}

async function handleDelete(account: GarminAccount) {
  try {
    await deleteAccount(account.id);
    ElMessage.success("已删除该 Garmin 账号绑定");
    await loadAccounts();
  } catch {
    // 错误提示由拦截器处理
  }
}

onMounted(loadAccounts);
</script>

<template>
  <section class="page-container">
    <PageHeading
      eyebrow="数据来源"
      title="Garmin 账号"
      description="在自己的电脑上安全登录 Garmin，绑定后由平台自动同步训练与健康数据。"
    />

    <el-card class="connect-card">
      <template #header>
        <strong>绑定 Garmin 账号</strong>
        <span class="card-hint">推荐使用桌面助手</span>
      </template>
      <el-alert type="info" :closable="false" class="binding-intro" show-icon>
        <template #default>
          Garmin
          会限制云服务器登录。桌面助手在你的电脑上完成认证，密码不会经过本平台；
          绑定成功后只将可撤销的登录令牌加密保存。
        </template>
      </el-alert>
      <div class="binding-consent">
        <el-checkbox v-model="sensitiveDataConsent" class="legal-consent">
          我单独同意平台按
          <router-link to="/legal/privacy" target="_blank"
            >《隐私政策》</router-link
          >
          处理我的健康与训练数据，用于同步、恢复评估和训练计划。
        </el-checkbox>
      </div>
      <div class="binding-actions">
        <el-button
          type="primary"
          size="large"
          :loading="pairLoading"
          :disabled="pairLoading || !sensitiveDataConsent"
          @click="openPairDialog"
        >
          使用桌面助手绑定
        </el-button>
        <el-button
          size="large"
          :disabled="!sensitiveDataConsent"
          @click="importDialogVisible = true"
        >
          手动导入令牌
        </el-button>
      </div>
      <p class="binding-note">
        已有助手导出的令牌时可手动导入；平台不再接收 Garmin 账号密码。
      </p>
    </el-card>

    <el-card v-loading="loading" class="accounts-card">
      <template #header>
        <strong>已绑定账号</strong>
      </template>

      <el-alert
        v-if="errorMessage"
        :title="errorMessage"
        type="error"
        show-icon
        :closable="false"
      >
        <template #default>
          <el-button type="primary" link @click="loadAccounts"
            >重新加载</el-button
          >
        </template>
      </el-alert>

      <el-empty v-else-if="!hasAccounts" description="还没有绑定 Garmin 账号" />

      <el-table v-else :data="accounts" style="width: 100%">
        <el-table-column label="账号" prop="emailMasked" min-width="180" />
        <el-table-column label="站点" width="110">
          <template #default="{ row }">{{ regionLabel(row.region) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="140">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.authStatus)">
              {{ statusLabel(row.authStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="自动同步" width="120">
          <template #default="{ row }">
            <el-switch
              :model-value="row.syncEnabled"
              :active-value="1"
              :inactive-value="0"
              :loading="switchingId === row.id"
              @update:model-value="
                (value: number) => handleAutoSyncChange(row, value)
              "
            />
          </template>
        </el-table-column>
        <el-table-column label="最近同步" min-width="170">
          <template #default="{ row }">{{
            formatTime(row.lastSyncTime)
          }}</template>
        </el-table-column>
        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              :loading="syncingId === row.id"
              @click="handleSync(row)"
            >
              同步
            </el-button>
            <el-button
              link
              :loading="verifyingId === row.id"
              @click="handleVerify(row)"
            >
              校验令牌
            </el-button>
            <el-popconfirm
              title="删除后需要重新绑定，确定删除吗？"
              confirm-button-text="删除"
              cancel-button-text="取消"
              @confirm="handleDelete(row)"
            >
              <template #reference>
                <el-button link type="danger">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog
      v-model="backfillDialogVisible"
      title="首次拉取历史数据"
      width="460px"
      :close-on-click-modal="false"
    >
      <p class="mfa-hint">
        账号绑定成功。是否现在拉取最近
        <strong>{{ INITIAL_BACKFILL_DAYS }} 天</strong>的历史数据？
      </p>
      <p class="mfa-hint">
        选择「暂不」也不影响使用：之后每天上午 9:00 会自动同步当日数据。
      </p>
      <template #footer>
        <el-button :disabled="backfillRunning" @click="handleBackfill(false)"
          >暂不</el-button
        >
        <el-button
          type="primary"
          :loading="backfillRunning"
          :disabled="backfillRunning"
          @click="handleBackfill(true)"
        >
          拉取 {{ INITIAL_BACKFILL_DAYS }} 天
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="importDialogVisible"
      title="导入 Garmin 令牌"
      width="560px"
      :close-on-click-modal="false"
    >
      <el-alert type="warning" :closable="false" class="import-hint">
        <template #default>
          令牌等同账号凭据，只应粘贴到本机运行的服务。平台会先校验令牌可用，
          再使用 AES-GCM 加密存储，页面不会保留粘贴内容。
        </template>
      </el-alert>
      <el-form label-width="90px" @submit.prevent>
        <el-form-item label="邮箱">
          <el-input v-model="importForm.email" placeholder="Garmin 登录邮箱" />
        </el-form-item>
        <el-form-item label="站点">
          <el-select v-model="importForm.region" style="width: 200px">
            <el-option label="国际站（connect.garmin.com）" value="GLOBAL" />
            <el-option label="中国区（connect.garmin.cn）" value="CN" />
          </el-select>
        </el-form-item>
        <el-form-item label="令牌内容">
          <el-input
            v-model="importForm.tokenJson"
            type="textarea"
            :rows="5"
            placeholder='{"di_token":"...","di_refresh_token":"...","di_client_id":"..."}'
          />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="sensitiveDataConsent" class="legal-consent">
            我单独同意平台按
            <router-link to="/legal/privacy" target="_blank"
              >《隐私政策》</router-link
            >
            处理我的健康与训练数据。
          </el-checkbox>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="importDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="importSubmitting"
          :disabled="importSubmitting || !sensitiveDataConsent"
          @click="handleImportToken"
        >
          校验并导入
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="pairDialogVisible"
      title="用桌面助手绑定 Garmin"
      width="760px"
      class="pair-dialog"
      @closed="stopPairPolling"
    >
      <el-alert type="info" :closable="false" class="import-hint">
        <template #default>
          Garmin 的登录接口按 IP 限流，而这个平台所有用户共用一个出口
          IP，所以登录必须在<strong>你自己的电脑</strong>上完成。
          助手只把登录结果（令牌）交回平台，<strong>密码不经过平台</strong>。
        </template>
      </el-alert>

      <ol class="pair-steps">
        <li>
          下载助手
          <a :href="PAIR_HELPER_URL" :download="PAIR_HELPER_FILE">{{
            PAIR_HELPER_FILE
          }}</a>
          ，也可以直接执行下方命令，自动下载并启动。 请先安装
          <a
            href="https://www.python.org/downloads/"
            target="_blank"
            rel="noopener noreferrer"
            >Python 3.12 或更高版本</a
          >（Windows 安装时保留 Python Launcher）。
          <el-tabs model-value="mac" class="pair-command-tabs">
            <el-tab-pane label="macOS · 终端" name="mac">
              <el-button size="small" @click="copyPairCommands('mac')"
                >复制 macOS 完整命令</el-button
              >
              <pre class="pair-cmd">{{ pairCommands.mac }}</pre>
            </el-tab-pane>
            <el-tab-pane label="Windows · PowerShell" name="windows">
              <el-button size="small" @click="copyPairCommands('windows')"
                >复制 Windows 完整命令</el-button
              >
              <pre class="pair-cmd">{{ pairCommands.windows }}</pre>
              <p class="pair-hint">
                在 PowerShell 中运行，无需管理员权限或修改执行策略。请勿粘贴到
                CMD。
              </p>
            </el-tab-pane>
          </el-tabs>
          <span class="pair-hint"
            >命令会在用户目录创建
            <code>garmin-pair-helper</code> 文件夹，依赖安装到其中的独立环境。
            首次安装需要下载浏览器，请保持终端打开，安装完成后再领取配对码。
            命令已开启手动登录：账号密码和验证码只在弹出的 Garmin
            官方页面填写。</span
          >
        </li>
        <li>
          助手启动后，把下面这个配对码填进去
          <div class="pair-code-row">
            <span class="pair-code">{{ pairCode }}</span>
            <el-button link type="primary" @click="copyPairCode"
              >复制</el-button
            >
          </div>
          <span class="pair-hint"
            >配对码
            {{ Math.round(pairSeconds / 60) }} 分钟内有效，只能用一次</span
          >
        </li>
        <li>
          在助手里填配对码、Garmin 邮箱和站点，点「开始绑定」，然后在弹出的
          Garmin 官方页面完成登录
        </li>
        <li>助手提示成功后，这个窗口会自动关闭并刷新账号列表</li>
      </ol>

      <el-alert type="warning" :closable="false" class="import-hint">
        <template #default>
          浏览器登录仍可能遇到 Garmin
          限流，请不要连续点击。若登录成功后配对码已过期，
          领取新码并在助手里重新提交即可，无需再次登录 Garmin。
        </template>
      </el-alert>

      <template #footer>
        <el-button @click="pairDialogVisible = false">关闭</el-button>
        <el-button
          type="primary"
          :loading="pairLoading"
          @click="openPairDialog"
        >
          重新获取配对码
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.binding-intro {
  margin-bottom: 18px;
}

.binding-consent {
  margin-bottom: 16px;
}

.binding-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.binding-actions .el-button {
  margin-left: 0;
}

.binding-note {
  margin: 12px 0 0;
  color: #909399;
  font-size: 13px;
}

.pair-steps {
  margin: 12px 0;
  padding-left: 20px;
  line-height: 1.9;
  font-size: 13px;
  color: #303133;
}

.pair-code-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 8px 0 2px;
}

.pair-code {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 26px;
  font-weight: 700;
  letter-spacing: 4px;
  color: #147662;
  background: #eef5f3;
  border-left: 4px solid #2ca58d;
  border-radius: 4px;
  padding: 8px 14px;
  /* 复制失败时用户可以直接点选整串，不必逐字拖 */
  user-select: all;
}

.pair-hint {
  font-size: 12px;
  color: #909399;
}

:global(.pair-dialog) {
  max-width: calc(100vw - 32px);
}

.pair-command-tabs {
  margin: 8px 0;
}

/* 给终端用户直接可复制的命令块：他们看不到仓库里的文档 */
.pair-cmd {
  margin: 8px 0 4px;
  padding: 8px 10px;
  background: #f4f4f5;
  border-radius: 4px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-all;
  user-select: all;
}
</style>
