"use client";

import { useCallback, useEffect, useState } from "react";
import {
  ApiError,
  createPolicy,
  deletePolicy,
  listPolicies,
  updatePolicy,
} from "@/lib/api";
import { DeniedNote } from "@/components/DeniedNote";
import { useAuth } from "@/lib/auth";
import type { CreatePolicyRequest, Effect, PolicyDto } from "@/lib/types";

const EMPTY: CreatePolicyRequest = {
  name: "",
  description: "",
  effect: "PERMIT",
  resourceType: "DOCUMENT",
  action: "READ",
  condition: "",
  priority: 50,
  enabled: true,
};

const COND_HINT = [
  "subject.clearance >= resource.requiredClearance",
  "subject.department == resource.department",
  "subject.region == 'CN'",
  "resource.owner == subject.username",
  "subject.title == 'manager' or subject.title == 'admin'",
  "resource.status == 'PUBLISHED'",
];

export default function PoliciesPanel() {
  const { token, user } = useAuth();
  const [items, setItems] = useState<PolicyDto[]>([]);
  const [err, setErr] = useState("");
const [denied, setDenied] = useState(false);
  const [ok, setOk] = useState("");
  const [form, setForm] = useState<CreatePolicyRequest>(EMPTY);
  const [editing, setEditing] = useState<number | null>(null);

  const load = useCallback(async () => {
    if (!token) return;
    try {
      setItems(await listPolicies(token));
      setErr("");
      setDenied(false);
    } catch (e) {
      const de = e instanceof ApiError && e.status === 403;
      setDenied(de);
      setErr(de ? "" : e instanceof Error ? e.message : "加载策略失败");
    }
  }, [token]);

  useEffect(() => {
    void load();
  }, [load]);

  async function submit() {
    if (!token) return;
    setErr("");
    setOk("");
    try {
      if (editing !== null) {
        await updatePolicy(token, editing, form);
        setOk("策略已更新");
        setEditing(null);
      } else {
        await createPolicy(token, form);
        setOk("策略已创建");
      }
      setForm(EMPTY);
      await load();
    } catch (e) {
      setErr(e instanceof Error ? e.message : "保存失败");
    }
  }

  async function remove(id: number) {
    if (!token) return;
    setErr("");
    setOk("");
    try {
      await deletePolicy(token, id);
      setOk("策略已删除");
      await load();
    } catch (e) {
      setErr(e instanceof Error ? e.message : "删除失败");
    }
  }

  async function toggle(p: PolicyDto) {
    if (!token) return;
    setErr("");
    setOk("");
    try {
      await updatePolicy(token, p.id, { enabled: !p.enabled });
      await load();
    } catch (e) {
      setErr(e instanceof Error ? e.message : "切换失败");
    }
  }

  // 策略写操作需要 admin；非 admin 只读，避免点了才报 403
  const canWrite = user?.title === "admin";

  return (
    <div>
      <div className="card">
        <h2>策略管理（PAP）</h2>
        <p className="sub">
          每条策略 = 作用范围（资源类型 + 动作）+ SpEL 条件 + 效果 + 优先级。
          DENY 优先（deny-override）；条件留空即无条件命中。
        </p>

        {canWrite && (
          <>
            <div className="row">
              <div className="field">
                <label>名称</label>
                <input
                  value={form.name}
                  onChange={(e) => setForm({ ...form, name: e.target.value })}
                  placeholder="如：同部门可读"
                />
              </div>
              <div className="field">
                <label>效果</label>
                <select
                  value={form.effect}
                  onChange={(e) =>
                    setForm({ ...form, effect: e.target.value as Effect })
                  }
                >
                  <option value="PERMIT">PERMIT</option>
                  <option value="DENY">DENY</option>
                </select>
              </div>
            </div>
            <div className="row">
              <div className="field">
                <label>资源类型（* 或不填 = 全部）</label>
                <input
                  value={form.resourceType ?? ""}
                  onChange={(e) => setForm({ ...form, resourceType: e.target.value })}
                />
              </div>
              <div className="field">
                <label>动作（* 或不填 = 全部）</label>
                <input
                  value={form.action ?? ""}
                  onChange={(e) => setForm({ ...form, action: e.target.value })}
                />
              </div>
              <div className="field">
                <label>优先级（越大越先判）</label>
                <input
                  type="number"
                  value={form.priority ?? 0}
                  onChange={(e) =>
                    setForm({ ...form, priority: Number(e.target.value) })
                  }
                />
              </div>
            </div>
            <div className="field">
              <label>SpEL 条件（可用 subject.* / resource.* / env.* / action）</label>
              <input
                className="cond"
                value={form.condition ?? ""}
                onChange={(e) => setForm({ ...form, condition: e.target.value })}
                placeholder="subject.clearance >= resource.requiredClearance"
              />
            </div>
            <div className="field">
              <label>说明</label>
              <input
                value={form.description ?? ""}
                onChange={(e) => setForm({ ...form, description: e.target.value })}
              />
            </div>

            <div className="hint">
              常用条件：{COND_HINT.map((c, i) => (
                <span key={c}>
                  <code>{c}</code>
                  {i < COND_HINT.length - 1 ? " · " : ""}
                </span>
              ))}
            </div>

            <div style={{ display: "flex", gap: 8 }}>
              <button className="btn" onClick={submit}>
                {editing !== null ? "保存修改" : "新增策略"}
              </button>
              {editing !== null && (
                <button
                  className="btn ghost"
                  onClick={() => {
                    setEditing(null);
                    setForm(EMPTY);
                  }}
                >
                  取消编辑
                </button>
              )}
            </div>
          </>
        )}

        {err && <div className="err" style={{ marginTop: 12 }}>{err}</div>}
        {ok && <div className="ok" style={{ marginTop: 12 }}>{ok}</div>}
      </div>

      <div className="card">
        <h2>策略列表</h2>
        <p className="sub">按优先级从高到低排列；判定遇到 DENY 立即拒绝，不再看后面的 PERMIT。</p>
        {denied ? (
          <DeniedNote
            policyRefs="POL-07 经理可列出策略 / POL-08 经理可查看策略"
            reason="当前身份未命中任何 PERMIT，触发默认拒绝（default deny）；策略面只对 manager/admin 开放。"
            suggest="切换 carol（manager）可只读查看全部策略；admin 还可增删改（POL-35 管理员可管理策略）。"
            switchTo="carol"
          />
) : (
<table className="pol-table">
          <thead>
            <tr>
              <th style={{ width: 40 }}>优先级</th>
              <th>名称</th>
              <th style={{ width: 70 }}>效果</th>
              <th style={{ width: 110 }}>范围</th>
              <th>条件（SpEL）</th>
              <th style={{ width: 150 }}>操作</th>
            </tr>
          </thead>
          <tbody>
            {items.map((p) => (
              <tr key={p.id} className={p.enabled ? "" : "disabled"}>
                <td>
                  <span className="prio">{p.priority}</span>
                </td>
                <td>
                  <div style={{ fontWeight: 600 }}>{p.name}</div>
                  {p.description && (
                    <div style={{ color: "var(--muted)", fontSize: 12 }}>
                      {p.description}
                    </div>
                  )}
                </td>
                <td>
                  <span
                    className={
                      p.effect === "PERMIT" ? "effect effect-permit" : "effect effect-deny"
                    }
                  >
                    {p.effect}
                  </span>
                </td>
                <td style={{ fontSize: 12 }}>
                  {p.resourceType || "*"} / {p.action || "*"}
                </td>
                <td className="code">
                  {p.condition ? (
                    <span className="cond">{p.condition}</span>
                  ) : (
                    <span className="cond empty">无条件</span>
                  )}
                </td>
                <td>
                  {canWrite && (
                    <>
                      <button
                        className="btn ghost"
                        style={{ padding: "5px 9px", fontSize: 12, marginRight: 6 }}
                        onClick={() => {
                          setEditing(p.id);
                          setForm({
                            name: p.name,
                            description: p.description ?? "",
                            effect: p.effect,
                            resourceType: p.resourceType ?? "",
                            action: p.action ?? "",
                            condition: p.condition ?? "",
                            priority: p.priority,
                            enabled: p.enabled,
                          });
                        }}
                      >
                        编辑
                      </button>
                      <button
                        className="btn ghost"
                        style={{ padding: "5px 9px", fontSize: 12, marginRight: 6 }}
                        onClick={() => toggle(p)}
                      >
                        {p.enabled ? "停用" : "启用"}
                      </button>
                      <button
                        className="btn btn-danger"
                        style={{ padding: "5px 9px", fontSize: 12 }}
                        onClick={() => remove(p.id)}
                      >
                        删除
                      </button>
                    </>
                  )}
                </td>
              </tr>
            ))}
            {items.length === 0 && (
              <tr>
                <td colSpan={6} style={{ textAlign: "center", color: "var(--muted)" }}>
                  暂无策略（若看不到，可能是当前主体的 title 不是 manager/admin）
                </td>
              </tr>
            )}
          </tbody>
        </table>
)}
      </div>
    </div>
  );
}
