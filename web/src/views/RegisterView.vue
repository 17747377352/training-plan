<script setup lang="ts">
import type { FormInstance, FormRules } from "element-plus";
import { reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { useAuthStore } from "../stores/auth";

const authStore = useAuthStore();
const router = useRouter();
const formRef = ref<FormInstance>();
const loading = ref(false);
const form = reactive({
  username: "",
  email: "",
  password: "",
  confirmPassword: "",
});
const rules: FormRules<typeof form> = {
  username: [
    { required: true, message: "请输入用户名", trigger: "blur" },
    {
      pattern: /^[a-zA-Z0-9_]{4,32}$/,
      message: "4-32 位，仅支持字母、数字和下划线",
      trigger: "blur",
    },
  ],
  email: [
    { required: true, message: "请输入邮箱", trigger: "blur" },
    { type: "email", message: "邮箱格式不正确", trigger: "blur" },
  ],
  password: [
    { required: true, message: "请输入密码", trigger: "blur" },
    { min: 8, max: 64, message: "密码长度需为 8-64 位", trigger: "blur" },
  ],
  confirmPassword: [
    { required: true, message: "请再次输入密码", trigger: "blur" },
    {
      validator: (_rule, value: string, callback) => {
        if (value !== form.password)
          callback(new Error("两次输入的密码不一致"));
        else callback();
      },
      trigger: "blur",
    },
  ],
};

async function submit(): Promise<void> {
  if (!(await formRef.value?.validate().catch(() => false))) return;
  loading.value = true;
  try {
    await authStore.register({
      username: form.username,
      email: form.email,
      password: form.password,
    });
    await router.replace("/");
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <section class="auth-page">
    <el-card class="auth-card">
      <div class="auth-heading">
        <h1>创建个人账号</h1>
        <p>每个用户独立管理自己的 Garmin 账号和训练数据。</p>
      </div>
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @keyup.enter="submit"
      >
        <el-form-item label="用户名" prop="username">
          <el-input
            v-model="form.username"
            autocomplete="username"
            placeholder="例如 runner_01"
          />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input
            v-model="form.email"
            autocomplete="email"
            placeholder="name@example.com"
          />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            autocomplete="new-password"
            show-password
          />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            autocomplete="new-password"
            show-password
          />
        </el-form-item>
        <el-button
          class="auth-submit"
          type="primary"
          :loading="loading"
          @click="submit"
        >
          注册并登录
        </el-button>
      </el-form>
      <p class="auth-switch">
        已有账号？<router-link to="/login">返回登录</router-link>
      </p>
    </el-card>
  </section>
</template>
