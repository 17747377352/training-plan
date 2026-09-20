<script setup lang="ts">
import type { FormInstance, FormRules } from "element-plus";
import { reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useAuthStore } from "../stores/auth";

const authStore = useAuthStore();
const route = useRoute();
const router = useRouter();
const formRef = ref<FormInstance>();
const loading = ref(false);
const form = reactive({ account: "", password: "" });
const rules: FormRules<typeof form> = {
  account: [{ required: true, message: "请输入用户名或邮箱", trigger: "blur" }],
  password: [{ required: true, message: "请输入密码", trigger: "blur" }],
};

async function submit(): Promise<void> {
  if (!(await formRef.value?.validate().catch(() => false))) return;
  loading.value = true;
  try {
    await authStore.login(form);
    const redirect =
      typeof route.query.redirect === "string" ? route.query.redirect : "/";
    await router.replace(redirect);
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <section class="auth-page">
    <el-card class="auth-card">
      <div class="auth-heading">
        <h1>登录 Training Plan</h1>
        <p>进入你的 Garmin 数据管理工作台。</p>
      </div>
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @keyup.enter="submit"
      >
        <el-form-item label="用户名或邮箱" prop="account">
          <el-input
            v-model="form.account"
            autocomplete="username"
            placeholder="请输入账号"
          />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            autocomplete="current-password"
            placeholder="请输入密码"
            show-password
          />
        </el-form-item>
        <el-button
          class="auth-submit"
          type="primary"
          :loading="loading"
          @click="submit"
        >
          登录
        </el-button>
      </el-form>
      <p class="auth-switch">
        还没有账号？<router-link to="/register">立即注册</router-link>
      </p>
    </el-card>
  </section>
</template>
