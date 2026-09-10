"use client";

import { useCallback, useEffect, useState } from "react";
import { getAuditStats, listAudit } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import type { AuditPage, AuditStats } from "@/lib/types";

export default function AuditPanel() {
  const { token } = useAuth();
  const [page, setPage] = useState<AuditPage | null>(null);
  const [stats, setStats] = useState<AuditStats | null>(null);
  const [p, setP] = useState(0);
  const [size, setSize] = useState(20);
  const [decision, setDecision] = useState("");
  const [actor, setActor] = useState("");
  const [err, setErr] = useState("");

  const load = useCallback(async () => {
    if (!token) return;
    try {
      const [pg, st] = await Promise.all([
        listAudit(token, {
          page: p,
          size,
          decision: decision || undefined,
          actor: actor || undefined,
        }),
        getAuditStats(token),
      ]);
      setPage(pg);
      setStats(st);
      setErr("");
    } catch (e) {
      setErr(e instanceof Error ? e.message : "加载审计失败");
    }
  }, [token, p, size, decision, actor]);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <div>
      <div className="card">
        <h2>裁决审计</h2>
        <p className="sub">
          网关 PEP 每裁决一次就发射一条审计事件（append-only）。相比 RBAC 版多记了
          <b>命中的策略</b>，可以回答"这条访问是被哪条策略放/挡的"。
        </p>
        {stats && (
          <div className="row" style={{ marginBottom: 12 }}>
            <div>
              总请求 <b>{stats.total}</b>
            </div>
            <div style={{ color: "var(--ok)" }}>
              放行 <b>{stats.allows}</b>
            </div>
            <div style={{ color: "var(--no)" }}>
              拒绝 <b>{stats.denies}</b>
            </div>
          </div>
        )}

        <div className="row">
          <div className="field">
            <label>裁决结果</label>
            <select value={decision} onChange={(e) => setDecision(e.target.value)}>
              <option value="">全部</option>
              <option value="ALLOW">仅放行</option>
              <option value="DENY">仅拒绝</option>
            </select>
          </div>
          <div className="field">
            <label>操作人</label>
            <input
              value={actor}
              onChange={(e) => setActor(e.target.value)}
              placeholder="如：bob"
            />
          </div>
        </div>
        <div style={{ display: "flex", gap: 8, marginTop: 8 }}>
          <button className="btn ghost" onClick={() => { setP(0); void load(); }}>
            筛选
          </button>
        </div>

        {err && <div className="err" style={{ marginTop: 12 }}>{err}</div>}
      </div>

      <div className="card">
        <h2>审计流水</h2>
        <table className="pol-table">
          <thead>
            <tr>
              <th style={{ width: 150 }}>时间</th>
              <th style={{ width: 90 }}>操作人</th>
              <th>动作</th>
              <th style={{ width: 80 }}>裁决</th>
              <th>命中策略</th>
              <th style={{ width: 60 }}>状态</th>
            </tr>
          </thead>
          <tbody>
            {(page?.content ?? []).map((a) => (
              <tr key={a.id}>
                <td style={{ fontSize: 12, color: "var(--muted)" }}>
                  {a.createdAt ? a.createdAt.replace("T", " ").slice(0, 19) : ""}
                </td>
                <td>{a.actor}</td>
                <td style={{ fontSize: 12 }}>
                  {a.action}
                  <div style={{ color: "var(--muted)" }}>
                    {a.method} {a.path}
                  </div>
                </td>
                <td>
                  <span
                    className={
                      a.decision === "ALLOW" ? "effect effect-permit" : "effect effect-deny"
                    }
                  >
                    {a.decision}
                  </span>
                </td>
                <td style={{ fontSize: 12 }}>
                  {a.policyName ?? <span style={{ color: "var(--muted)" }}>—</span>}
                  {a.reason && (
                    <div style={{ color: "var(--muted)" }}>{a.reason}</div>
                  )}
                </td>
                <td>{a.status ?? ""}</td>
              </tr>
            ))}
            {(page?.content.length ?? 0) === 0 && (
              <tr>
                <td colSpan={6} style={{ textAlign: "center", color: "var(--muted)" }}>
                  暂无记录（AUDIT:READ 只放开给 admin）
                </td>
              </tr>
            )}
          </tbody>
        </table>

        <div className="pager">
          <button
            className="btn ghost"
            disabled={p <= 0}
            onClick={() => setP(Math.max(p - 1, 0))}
          >
            上一页
          </button>
          <span className="pager-info">
            第 {(page?.number ?? 0) + 1} / {page?.totalPages ?? 1} 页
          </span>
          <button
            className="btn ghost"
            disabled={!!page && p + 1 >= page.totalPages}
            onClick={() => setP(p + 1)}
          >
            下一页
          </button>
          <span className="audit-size">
            每页
            <select
              value={size}
              onChange={(e) => {
                setSize(Number(e.target.value));
                setP(0);
              }}
            >
              {[10, 20, 50].map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </span>
        </div>
      </div>
    </div>
  );
}
