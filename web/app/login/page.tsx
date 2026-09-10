"use client";

import { useEffect, useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";

const DEPARTMENTS = ["ENG", "SALES", "EXEC"];
const REGIONS = ["CN", "US"];
const TITLES = ["engineer", "sales", "manager", "admin"];

export default function LoginPage() {
  const { login, register, token, ready } = useAuth();
  const router = useRouter();

  const [mode, setMode] = useState<"login" | "register">("login");
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("admin123");
  // 注册时可直接指定主体属性：注册完就能立刻用这份属性去撞策略
  const [department, setDepartment] = useState("ENG");
  const [clearance, setClearance] = useState(3);
  const [region, setRegion] = useState("CN");
  const [title, setTitle] = useState("engineer");
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (ready && token) router.replace("/");
  }, [ready, token, router]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setErr("");
    setBusy(true);
    try {
      if (mode === "login") {
        await login(username, password);
      } else {
        await register({ username, password, department, clearance, region, title });
      }
      router.replace("/");
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : "操作失败");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="auth-wrap">
      <div className="card">
        <h2>spring-abac 控制台</h2>
        <p className="sub">登录以管理策略、模拟裁决与访问受保护文档</p>

        <div className="auth-tabs">
          <button className={mode === "login" ? "on" : ""} onClick={() => setMode("login")}>
            登录
          </button>
          <button
            className={mode === "register" ? "on" : ""}
            onClick={() => setMode("register")}
          >
            注册
          </button>
        </div>

        <form onSubmit={submit}>
          <div className="field">
            <label>用户名</label>
            <input
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoComplete="username"
            />
          </div>
          <div className="field">
            <label>密码（至少 6 位）</label>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete={mode === "login" ? "current-password" : "new-password"}
            />
          </div>

          {mode === "register" && (
            <>
              <div className="hint" style={{ marginTop: 4 }}>
                ABAC 不看角色看属性：注册时直接指定部门 / 密级 / 地区 / 岗位，
                登录后的每个请求都会带着这些属性去撞策略。
              </div>
              <div className="row">
                <div className="field">
                  <label>部门 department</label>
                  <select value={department} onChange={(e) => setDepartment(e.target.value)}>
                    {DEPARTMENTS.map((d) => (
                      <option key={d} value={d}>
                        {d}
                      </option>
                    ))}
                  </select>
                </div>
                <div className="field">
                  <label>密级 clearance（1-5）</label>
                  <input
                    type="number"
                    min={1}
                    max={5}
                    value={clearance}
                    onChange={(e) => setClearance(Number(e.target.value))}
                  />
                </div>
              </div>
              <div className="row">
                <div className="field">
                  <label>地区 region</label>
                  <select value={region} onChange={(e) => setRegion(e.target.value)}>
                    {REGIONS.map((r) => (
                      <option key={r} value={r}>
                        {r}
                      </option>
                    ))}
                  </select>
                </div>
                <div className="field">
                  <label>岗位 title</label>
                  <select value={title} onChange={(e) => setTitle(e.target.value)}>
                    {TITLES.map((t) => (
                      <option key={t} value={t}>
                        {t}
                      </option>
                    ))}
                  </select>
                </div>
              </div>
            </>
          )}

          {err && <div className="err">{err}</div>}

          <button className="btn" style={{ width: "100%" }} disabled={busy}>
            {busy ? "处理中…" : mode === "login" ? "登录" : "注册并登录"}
          </button>
        </form>

        <div className="hint" style={{ marginTop: 14, marginBottom: 0 }}>
          演示账号（属性差异明显，同一条策略会给出不同裁决）：
          <br />
          <b>admin/admin123</b> — EXEC · clearance 5 · CN · admin（全通）
          <br />
          <b>carol/carol123</b> — ENG · clearance 4 · US · manager（境外地区，会被地区策略挡）
          <br />
          <b>alice/alice123</b> — ENG · clearance 3 · CN · engineer（普通工程师）
          <br />
          <b>bob/bob123</b> — SALES · clearance 2 · CN · sales（跨部门 + 低密级）
        </div>
      </div>
    </div>
  );
}
