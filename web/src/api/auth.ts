import type { AuthTokens, UserProfile } from "../types/api";
import { request } from "../utils/request";

export interface RegisterForm {
  username: string;
  email: string;
  password: string;
}

export interface LoginForm {
  account: string;
  password: string;
}

/**
 * 是否开放自助注册。
 *
 * 公开接口，只返回一个布尔值；注册页据此在提交前如实告知，
 * 而不是让人填完表单再失败。
 */
export function isRegistrationEnabled(): Promise<boolean> {
  return request({ method: "GET", url: "/api/auth/registration-enabled" });
}

export function register(form: RegisterForm): Promise<UserProfile> {
  return request({ method: "POST", url: "/api/auth/register", data: form });
}

export function login(form: LoginForm): Promise<AuthTokens> {
  return request({ method: "POST", url: "/api/auth/login", data: form });
}

export function logout(refreshToken: string): Promise<void> {
  return request({
    method: "POST",
    url: "/api/auth/logout",
    data: { refreshToken },
  });
}

export function getCurrentUser(): Promise<UserProfile> {
  return request({ method: "GET", url: "/api/users/me" });
}
