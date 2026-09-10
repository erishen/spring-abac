"use client";

import { useState } from "react";
import { decide } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import type { DecisionRequest, DecisionResponse } from "@/lib/types";
import { AttrBar } from "./AttrBar";

const ACTIONS = ["READ", "CREATE", "UPDATE", "DELETE", "PUBLISH"];
const CLASSIFICATIONS = ["PUBLIC", "INTERNAL", "CONFIDENTIAL", "SECRET"];
const STATUSES = ["DRAFT", "PUBLISHED", "ARCHIVED"];

/** 密级 → 所需 clearance，与后端 Document.requiredClearanceOf 保持一致。 */
const REQUIRED: Record<string, number> = {
  PUBLIC: 1,
  INTERNAL: 2,
  CONFIDENTIAL: 4,
  SECRET: 5,
};

export default function SimulatorPanel() {
  const { token, user } = useAuth();

  // 主体属性默认取当前登录者，可随意改着玩——改完立刻能看到裁决翻转
  const [username, setUsername] = useState(user?.username ?? "");
  const [department, setDepartment] = useState(user?.department ?? "ENG");
  const [clearance, setClearance] = useState(user?.clearance ?? 3);
  const [region, setRegion] = useState(user?.region ?? "CN");
  const [title, setTitle] = useState(user?.title ?? "engineer");

  // 资源属性
  const [resourceType, setResourceType] = useState("DOCUMENT");
  const [owner, setOwner] = useState("alice");
  const [resDept, setResDept] = useState("ENG");
  const [classification, setClassification] = useState("INTERNAL");
  const [status, setStatus] = useState("DRAFT");
  const [action, setAction] = useState("READ");

  // 环境属性（默认取当前小时，可手工调到 20 试非工作时间删文档）
  const [envHour, setEnvHour] = useState(new Date().getHours());

  const [result, setResult] = useState<DecisionResponse | null>(null);
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);

  async function run() {
    if (!token) return;
    setErr("");
    setBusy(true);
    try {
      const body: DecisionRequest = {
        subject: { username, department, clearance, region, title },
        resource: {
          type: resourceType,
          attributes: {
            type: resourceType,
            owner,
            department: resDept,
            classification,
            requiredClearance: REQUIRED[classification] ?? 1,
            status,
          },
        },
        action,
        environment: { hour: envHour },
      };
      setResult(await decide(token, body));
    } catch (e) {
      setErr(e instanceof Error ? e.message : "裁决失败");
      setResult(null);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div>
      <div className="card">
        <h2>裁决模拟器（直接问 PDP）</h2>
        <p className="sub">
          手工拼一份「主体 + 资源 + 环境」属性包，直接调 PDP 的 /api/decide。
          不落库、不改数据，纯粹用来观察属性怎么影响结论——把 region 从 CN 改成 US，
          或把 clearance 调到低于密级要求，裁决会立刻翻转。
        </p>

        <div style={{ fontWeight: 600, marginBottom: 6 }}>主体属性 subject</div>
        <div className="row">
          <div className="field">
            <label>username</label>
            <input value={username} onChange={(e) => setUsername(e.target.value)} />
          </div>
          <div className="field">
            <label>department</label>
            <input value={department} onChange={(e) => setDepartment(e.target.value)} />
          </div>
          <div className="field">
            <label>clearance（1-5）</label>
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
            <label>region</label>
            <select value={region} onChange={(e) => setRegion(e.target.value)}>
              <option value="CN">CN</option>
              <option value="US">US</option>
            </select>
          </div>
          <div className="field">
            <label>title</label>
            <select value={title} onChange={(e) => setTitle(e.target.value)}>
              <option value="engineer">engineer</option>
              <option value="sales">sales</option>
              <option value="manager">manager</option>
              <option value="admin">admin</option>
            </select>
          </div>
          <div className="field">
            <label>动作 action</label>
            <select value={action} onChange={(e) => setAction(e.target.value)}>
              {ACTIONS.map((a) => (
                <option key={a} value={a}>
                  {a}
                </option>
              ))}
            </select>
          </div>
        </div>

        <div style={{ fontWeight: 600, margin: "12px 0 6px" }}>资源属性 resource</div>
        <div className="row">
          <div className="field">
            <label>type</label>
            <input value={resourceType} onChange={(e) => setResourceType(e.target.value)} />
          </div>
          <div className="field">
            <label>owner</label>
            <input value={owner} onChange={(e) => setOwner(e.target.value)} />
          </div>
          <div className="field">
            <label>department</label>
            <input value={resDept} onChange={(e) => setResDept(e.target.value)} />
          </div>
        </div>
        <div className="row">
          <div className="field">
            <label>classification</label>
            <select
              value={classification}
              onChange={(e) => setClassification(e.target.value)}
            >
              {CLASSIFICATIONS.map((c) => (
                <option key={c} value={c}>
                  {c}（需 clearance {REQUIRED[c]}）
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label>status</label>
            <select value={status} onChange={(e) => setStatus(e.target.value)}>
              {STATUSES.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label>环境 env.hour（0-23）</label>
            <input
              type="number"
              min={0}
              max={23}
              value={envHour}
              onChange={(e) => setEnvHour(Number(e.target.value))}
            />
          </div>
        </div>

        <AttrBar label="本次输入：" attrs={{ hour: envHour }} kind="env" />

        <button className="btn" style={{ marginTop: 10 }} onClick={run} disabled={busy}>
          {busy ? "裁决中…" : "问 PDP：能不能" + action + "？"}
        </button>

        {err && <div className="err" style={{ marginTop: 12 }}>{err}</div>}

        {result && (
          <>
            <div className={`verdict ${result.permitted ? "allow" : "deny"}`}>
              {result.permitted ? "PERMIT 允许" : "DENY 拒绝"}
            </div>
            <div className="verdict-why">
              命中策略：<b>{result.policyName ?? "无（默认拒绝）"}</b>
              {result.reason ? ` — ${result.reason}` : ""}
            </div>
          </>
        )}
      </div>

      {result && result.trace.length > 0 && (
        <div className="card">
          <h2>判定过程</h2>
          <p className="sub">
            按优先级从高到低逐条求值。DENY 一旦命中立即短路返回（deny-override），
            后面的 PERMIT 策略根本不会被看到。
          </p>
          <table className="pol-table">
            <thead>
              <tr>
                <th>策略</th>
                <th style={{ width: 80 }}>效果</th>
                <th style={{ width: 90 }}>是否命中</th>
                <th>异常</th>
              </tr>
            </thead>
            <tbody>
              {result.trace.map((t, i) => (
                <tr key={i}>
                  <td>{t.policyName}</td>
                  <td>
                    <span
                      className={
                        t.effect === "PERMIT" ? "effect effect-permit" : "effect effect-deny"
                      }
                    >
                      {t.effect}
                    </span>
                  </td>
                  <td>
                    {t.matched ? (
                      <span className="effect effect-permit">命中</span>
                    ) : (
                      <span style={{ color: "var(--muted)" }}>未命中</span>
                    )}
                  </td>
                  <td style={{ color: "var(--no)", fontSize: 12 }}>{t.error ?? ""}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
