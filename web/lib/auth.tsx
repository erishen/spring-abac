"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from "react";
import { login as loginApi, me, register as registerApi } from "./api";
import type { UserInfo } from "./types";

interface AuthState {
  token: string | null;
  /** 当前登录者（含主体属性）。 */
  user: UserInfo | null;
  ready: boolean;
  login: (username: string, password: string) => Promise<void>;
  register: (body: { username: string; password: string }) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthState | null>(null);
const TOKEN_KEY = "abac_token";

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setToken] = useState<string | null>(null);
  const [user, setUser] = useState<UserInfo | null>(null);
  const [ready, setReady] = useState(false);

  // 启动时从 localStorage 恢复会话，并顺带校验 token 是否仍有效。
  useEffect(() => {
    const t = localStorage.getItem(TOKEN_KEY);
    if (!t) {
      setReady(true);
      return;
    }
    setToken(t);
    me(t)
      .then(setUser)
      .catch(() => {
        localStorage.removeItem(TOKEN_KEY);
        setToken(null);
      })
      .finally(() => setReady(true));
  }, []);

  const login = useCallback(async (username: string, password: string) => {
    const r = await loginApi(username, password);
    localStorage.setItem(TOKEN_KEY, r.token);
    setToken(r.token);
    const u = await me(r.token);
    setUser(u);
  }, []);

  const register = useCallback(
    async (body: {
      username: string;
      password: string;
      department?: string;
      clearance?: number;
      region?: string;
      title?: string;
    }) => {
      await registerApi(body);
      await login(body.username, body.password);
    },
    [login],
  );

  const logout = useCallback(() => {
    localStorage.removeItem(TOKEN_KEY);
    setToken(null);
    setUser(null);
  }, []);

  return (
    <AuthContext.Provider value={{ token, user, ready, login, register, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth 必须在 AuthProvider 内使用");
  return ctx;
}
