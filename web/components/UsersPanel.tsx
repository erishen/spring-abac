"use client";

import { useCallback, useEffect, useState } from "react";
import { listUsers, updateUserAttributes } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import type { UserDto } from "@/lib/types";
import { AttrBar } from "./AttrBar";
import { CLEARANCE_LABELS, clearanceLabel } from "@/lib/clearance";

export default function UsersPanel() {
  const { token, user } = useAuth();
  const [items, setItems] = useState<UserDto[]>([]);
  const [err, setErr] = useState("");
  const [ok, setOk] = useState("");
  const [editing, setEditing] = useState<string | null>(null);
  const [draft, setDraft] = useState({
    department: "ENG",
    clearance: 3,
    region: "CN",
    title: "engineer",
  });

  const load = useCallback(async () => {
    if (!token) return;
    try {
      setItems(await listUsers(token));
      setErr("");
    } catch (e) {
      setErr(e instanceof Error ? e.message : "加载用户失败");
    }
  }, [token]);

  useEffect(() => {
    void load();
  }, [load]);

  async function save(username: string) {
    if (!token) return;
    setErr("");
    setOk("");
    try {
      await updateUserAttributes(token, username, draft);
      setOk(`${username} 的属性已更新（其下次登录签发的新 JWT 即带上新属性）`);
      setEditing(null);
      await load();
    } catch (e) {
      setErr(e instanceof Error ? e.message : "保存失败");
    }
  }

  // 改属性 = 改权限，只放开 admin（与后端 P-60 一致，避免点了才 403）
  const canEdit = user?.title === "admin";

  return (
    <div>
      <div className="card">
        <h2>用户与主体属性</h2>
        <p className="sub">
          ABAC 里没有"给用户配角色"这一步——用户身上带着属性，
          策略直接读属性判定。改一个用户的 clearance / region / title，
          他的可见范围立刻改变。
        </p>

        {err && <div className="err">{err}</div>}
        {ok && <div className="ok">{ok}</div>}

        <table className="pol-table">
          <thead>
            <tr>
              <th>用户名</th>
              <th>department</th>
              <th>clearance</th>
              <th>region</th>
              <th>title</th>
              <th style={{ width: 120 }}>操作</th>
            </tr>
          </thead>
          <tbody>
            {items.map((u) => (
              <tr key={u.id}>
                <td style={{ fontWeight: 600 }}>{u.username}</td>
                <td>{u.department}</td>
                <td>
                  {u.clearance} · {clearanceLabel(u.clearance)}
                </td>
                <td>{u.region}</td>
                <td>{u.title}</td>
                <td>
                  {canEdit && (
                    <button
                      className="btn ghost"
                      style={{ padding: "5px 9px", fontSize: 12 }}
                      onClick={() => {
                        setEditing(u.username);
                        setDraft({
                          department: u.department ?? "ENG",
                          clearance: u.clearance ?? 1,
                          region: u.region ?? "CN",
                          title: u.title ?? "engineer",
                        });
                      }}
                    >
                      改属性
                    </button>
                  )}
                </td>
              </tr>
            ))}
            {items.length === 0 && (
              <tr>
                <td colSpan={6} style={{ textAlign: "center", color: "var(--muted)" }}>
                  看不到用户（USER:READ 只放开给 manager / admin）
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {editing && (
        <div className="card">
          <h2>编辑 {editing} 的主体属性</h2>
          <p className="sub">保存后，该用户下次登录拿到的 JWT 就带新属性，裁决结果随之改变。</p>
          <div className="row">
            <div className="field">
              <label>department</label>
              <input
                value={draft.department}
                onChange={(e) => setDraft({ ...draft, department: e.target.value })}
              />
            </div>
            <div className="field">
              <label>clearance（1-5）</label>
              <select
                value={draft.clearance}
                onChange={(e) => setDraft({ ...draft, clearance: Number(e.target.value) })}
              >
                {Object.entries(CLEARANCE_LABELS).map(([v, label]) => (
                  <option key={v} value={v}>
                    {v} · {label}
                  </option>
                ))}
              </select>
            </div>
            <div className="field">
              <label>region</label>
              <select
                value={draft.region}
                onChange={(e) => setDraft({ ...draft, region: e.target.value })}
              >
                <option value="CN">CN</option>
                <option value="US">US</option>
              </select>
            </div>
            <div className="field">
              <label>title</label>
              <select
                value={draft.title}
                onChange={(e) => setDraft({ ...draft, title: e.target.value })}
              >
                <option value="engineer">engineer</option>
                <option value="sales">sales</option>
                <option value="manager">manager</option>
                <option value="admin">admin</option>
              </select>
            </div>
          </div>
          <div style={{ display: "flex", gap: 8 }}>
            <button className="btn" onClick={() => save(editing)}>
              保存
            </button>
            <button className="btn ghost" onClick={() => setEditing(null)}>
              取消
            </button>
          </div>
        </div>
      )}

      {user && (
        <div className="card">
          <h2>我当前的属性</h2>
          <p className="sub">以下属性随每个请求透传给 PDP，是所有判定的输入。</p>
          <AttrBar
            label="subject："
            kind="sub"
            attrs={{
              username: user.username,
              department: user.department,
              clearance: user.clearance,
              region: user.region,
              title: user.title,
            }}
          />
        </div>
      )}
    </div>
  );
}
