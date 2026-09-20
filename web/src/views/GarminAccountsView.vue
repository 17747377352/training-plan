<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import {
  connectAccount,
  deleteAccount,
  listAccounts,
  submitMfa,
  updateAutoSync,
  verifyAccount,
} from "../api/garmin";
import type { GarminAccount, GarminAuthStatus, GarminRegion } from "../types/api";

const router = useRouter();

const accounts = ref<GarminAccount[]>([]);
const loading = ref(false);
const errorMessage = ref("");
const connecting = ref(false);
const verifyingId = ref<number>();
const switchingId = ref<number>();

const connectForm = reactive({
  email: "",
  password: "",
  region: "CN" as GarminRegion,
});

const mfaDialogVisible = ref(false);
const mfaCode = ref("");
const mfaSubmitting = ref(false);
const loginSessionId = ref("");

const STATUS_LABELS: Record<GarminAuthStatus, string> = {
  PENDING: "待认证",
  PENDING_MFA: "待输入验证码",
  ACTIVE: "已连接",
  REAUTH_REQUIRED: "需要重新认证",
};

const STATUS_TAG_TYPES: Record<GarminAuthStatus, "success" | "info" | "warning" | "danger"> = {
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

async function handleConnect() {
  if (connecting.value) return;
  if (!connectForm.email || !connectForm.password) {
    ElMessage.warning("请填写 Garmin 邮箱和密码");
    return;
  }
  connecting.value = true;
  try {
    const result = await connectAccount({ ...connectForm });
    // 密码不留在前端内存里
    connectForm.password = "";
    if (result.status === "MFA_REQUIRED" && result.loginSessionId) {
      loginSessionId.value = result.loginSessionId;
      mfaCode.value = "";
      mfaDialogVisible.value = true;
      ElMessage.info("Garmin 需要二次验证，请输入验证码");
      return;
    }
    ElMessage.success("Garmin 账号连接成功");
    await loadAccounts();
  } catch {
    // 错误提示由请求拦截器统一处理
  } finally {
    connecting.value = false;
  }
}

async function handleSubmitMfa() {
  if (mfaSubmitting.value) return;
  if (!mfaCode.value) {
    ElMessage.warning("请输入验证码");
    return;
  }
  mfaSubmitting.value = true;
  try {
    const result = await submitMfa(loginSessionId.value, mfaCode.value.trim());
    if (result.status === "MFA_REQUIRED") {
      ElMessage.warning("验证码不正确，请重新输入");
      return;
    }
    mfaDialogVisible.value = false;
    mfaCode.value = "";
    ElMessage.success("Garmin 账号连接成功");
    await loadAccounts();
  } catch {
    // 会话仍保留，允许用户重试；错误提示由拦截器处理
  } finally {
    mfaSubmitting.value = false;
  }
}

function handleCancelMfa() {
  mfaDialogVisible.value = false;
  mfaCode.value = "";
  ElMessage.info("已取消验证码输入，可稍后重新连接");
}

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
    <header class="page-heading">
      <div>
        <h1>Garmin 账号</h1>
        <p>绑定 Garmin 账号后，平台才能同步你的健康与训练数据。</p>
      </div>
      <el-button @click="router.push('/')">返回数据管理</el-button>
    </header>

    <el-card class="connect-card">
      <template #header>
        <strong>添加 Garmin 账号</strong>
        <span class="card-hint">密码只用于本次登录，不会保存</span>
      </template>
      <el-form label-width="90px" @submit.prevent>
        <el-form-item label="邮箱">
          <el-input
            v-model="connectForm.email"
            placeholder="Garmin 登录邮箱"
            autocomplete="off"
          />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="connectForm.password"
            type="password"
            show-password
            placeholder="Garmin 登录密码"
            autocomplete="new-password"
          />
        </el-form-item>
        <el-form-item label="站点">
          <el-select v-model="connectForm.region" style="width: 200px">
            <el-option label="中国区（connect.garmin.cn）" value="CN" />
            <el-option label="国际站（connect.garmin.com）" value="GLOBAL" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            :loading="connecting"
            :disabled="connecting"
            @click="handleConnect"
          >
            连接 Garmin
          </el-button>
        </el-form-item>
      </el-form>
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
          <el-button type="primary" link @click="loadAccounts">重新加载</el-button>
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
              @update:model-value="(value: number) => handleAutoSyncChange(row, value)"
            />
          </template>
        </el-table-column>
        <el-table-column label="最近同步" min-width="170">
          <template #default="{ row }">{{ formatTime(row.lastSyncTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
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
      v-model="mfaDialogVisible"
      title="输入 Garmin 验证码"
      width="420px"
      :close-on-click-modal="false"
      @close="handleCancelMfa"
    >
      <p class="mfa-hint">
        Garmin 已向你的邮箱或验证器发送验证码，请在有效期内输入。
      </p>
      <el-input
        v-model="mfaCode"
        placeholder="6 位验证码"
        maxlength="16"
        @keyup.enter="handleSubmitMfa"
      />
      <template #footer>
        <el-button @click="handleCancelMfa">取消</el-button>
        <el-button
          type="primary"
          :loading="mfaSubmitting"
          :disabled="mfaSubmitting"
          @click="handleSubmitMfa"
        >
          提交验证码
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>
