"use client";

import { useEffect, useRef, useState } from "react";
import { decide } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import type { DecisionRequest, DecisionResponse } from "@/lib/types";
import { AttrBar } from "./AttrBar";

const ACTIONS = [
  "READ",
  "CREATE",
  "UPDATE",
  "DELETE",
  "PUBLISH",
  "LIST",
  "EXECUTE",
  "FETCH",
  "SEND",
  "TRANSFER",
];

/** 快捷添加的常用资源属性键，覆盖交易 / Agent 域的典型字段。 */
const QUICK_KEYS = [
  "amount",
  "channel",
  "region",
  "urlDomain",
  "code",
  "recipientCount",
  "sessionFetchCount",
  "sessionSendCount",
  "cumulativeAfter",
  "path",
];

/** 键值对行：扩展属性编辑器的数据模型。 */
interface KvRow {
  id: number;
  key: string;
  value: string;
  /** 快捷添加命中已有键时置 true，渲染后聚焦该行。 */
  focus?: boolean;
}

/** 把用户输入的值转成后端能识别的类型：数字 / 布尔 / 字符串。 */
function inferValue(s: string): unknown {
  const t = s.trim();
  if (t === "") return "";
  if (/^-?\d+(\.\d+)?$/.test(t)) return Number(t);
  if (t === "true") return true;
  if (t === "false") return false;
  return t;
}
const CLASSIFICATIONS = ["PUBLIC", "INTERNAL", "CONFIDENTIAL", "SECRET"];
const STATUSES = ["DRAFT", "PUBLISHED", "ARCHIVED"];

/** 密级 → 所需 clearance，与后端 Document.requiredClearanceOf 保持一致。 */
const REQUIRED: Record<string, number> = {
  PUBLIC: 1,
  INTERNAL: 2,
  CONFIDENTIAL: 4,
  SECRET: 5,
};

/** 预设场景：点选即填充属性包并自动裁决，覆盖文档 / 交易 / Agent 三个域与三种效果。 */
interface Scenario {
  id: string;
  group: "文档" | "交易" | "Agent";
  label: string;
  /** 预期命中策略，展示在按钮上帮助理解。 */
  expect: string;
  expectKind: "PERMIT" | "DENY" | "REVIEW";
  subject: Record<string, unknown>;
  resourceType: string;
  resource: Record<string, unknown>;
  action: string;
  envHour?: number;
}

const SCENARIOS: Scenario[] = [
  // ---- 文档域 ----
  {
    id: "doc-read-internal",
    group: "文档",
    label: "工程师读本部门内部文档",
    expect: "DOC-20 同部门可读",
    expectKind: "PERMIT",
    subject: { username: "alice", department: "ENG", clearance: 3, region: "CN", title: "engineer" },
    resourceType: "DOCUMENT",
    resource: { type: "DOCUMENT", owner: "alice", department: "ENG", classification: "INTERNAL", requiredClearance: 2, status: "PUBLISHED" },
    action: "READ",
  },
  {
    id: "doc-read-secret-deny",
    group: "文档",
    label: "销售读机密文档（密级不足）",
    expect: "DOC-90 密级不足拒绝",
    expectKind: "DENY",
    subject: { username: "bob", department: "SALES", clearance: 2, region: "CN", title: "sales" },
    resourceType: "DOCUMENT",
    resource: { type: "DOCUMENT", owner: "carol", department: "SALES", classification: "CONFIDENTIAL", requiredClearance: 4, status: "PUBLISHED" },
    action: "READ",
  },
  {
    id: "doc-read-overseas",
    group: "文档",
    label: "境外经理读机密文档（数据属地）",
    expect: "DOC-95 境外禁读机密",
    expectKind: "DENY",
    subject: { username: "carol", department: "ENG", clearance: 4, region: "US", title: "manager" },
    resourceType: "DOCUMENT",
    resource: { type: "DOCUMENT", owner: "alice", department: "ENG", classification: "CONFIDENTIAL", requiredClearance: 4, status: "PUBLISHED" },
    action: "READ",
  },
  {
    id: "doc-read-secret-admin",
    group: "文档",
    label: "管理员读绝密文档",
    expect: "DOC-05 管理员全权",
    expectKind: "PERMIT",
    subject: { username: "admin", department: "EXEC", clearance: 5, region: "CN", title: "admin" },
    resourceType: "DOCUMENT",
    resource: { type: "DOCUMENT", owner: "carol", department: "ENG", classification: "SECRET", requiredClearance: 5, status: "PUBLISHED" },
    action: "READ",
  },
  {
    id: "doc-delete-late",
    group: "文档",
    label: "22 点删文档（环境约束，admin 也被挡）",
    expect: "DOC-100 非工作时间禁止删除",
    expectKind: "DENY",
    subject: { username: "admin", department: "EXEC", clearance: 5, region: "CN", title: "admin" },
    resourceType: "DOCUMENT",
    resource: { type: "DOCUMENT", owner: "alice", department: "ENG", classification: "INTERNAL", requiredClearance: 2, status: "DRAFT" },
    action: "DELETE",
    envHour: 22,
  },
  // ---- 交易域 ----
  {
    id: "trade-large-review",
    group: "交易",
    label: "大额交易 6 万（转人工复核）",
    expect: "TRD-85 大额转复核",
    expectKind: "REVIEW",
    subject: { username: "admin", department: "EXEC", clearance: 5, region: "CN", title: "admin" },
    resourceType: "TRADE",
    resource: { type: "TRADE", amount: 60000, channel: "MOBILE", region: "CN" },
    action: "EXECUTE",
  },
  {
    id: "trade-engineer-limit",
    group: "交易",
    label: "工程师单笔 3 万（超岗位限额）",
    expect: "TRD-70 工程师限额",
    expectKind: "DENY",
    subject: { username: "alice", department: "ENG", clearance: 3, region: "CN", title: "engineer" },
    resourceType: "TRADE",
    resource: { type: "TRADE", amount: 30000, channel: "MOBILE", region: "CN" },
    action: "EXECUTE",
  },
  {
    id: "trade-web-overseas",
    group: "交易",
    label: "境外 + 网页渠道下单",
    expect: "TRD-75 境外网页渠道禁止",
    expectKind: "DENY",
    subject: { username: "carol", department: "ENG", clearance: 4, region: "US", title: "manager" },
    resourceType: "TRADE",
    resource: { type: "TRADE", amount: 10000, channel: "WEB", region: "US" },
    action: "EXECUTE",
  },
  // ---- Agent 域 ----
  {
    id: "agent-url-denylist",
    group: "Agent",
    label: "Agent 外联非白名单域名",
    expect: "WEB-95 外链白名单",
    expectKind: "DENY",
    subject: { username: "research-agent", department: "TOOL", clearance: 3, region: "CN", title: "agent", trust: "high" },
    resourceType: "WEB",
    resource: { type: "WEB", urlDomain: "evil.com" },
    action: "FETCH",
  },
  {
    id: "agent-code-rmrf",
    group: "Agent",
    label: "Agent 执行 rm -rf 代码",
    expect: "COD-80 危险命令禁止",
    expectKind: "DENY",
    subject: { username: "coder-agent", department: "TOOL", clearance: 3, region: "CN", title: "agent", trust: "medium" },
    resourceType: "CODE",
    resource: { type: "CODE", code: "rm -rf /tmp/cache" },
    action: "EXECUTE",
  },
  {
    id: "agent-code-process",
    group: "Agent",
    label: "Agent 启动子进程（转人工复核）",
    expect: "COD-75 高危代码转复核",
    expectKind: "REVIEW",
    subject: { username: "coder-agent", department: "TOOL", clearance: 3, region: "CN", title: "agent", trust: "medium" },
    resourceType: "CODE",
    resource: { type: "CODE", code: "new ProcessBuilder(\"ls\").start()" },
    action: "EXECUTE",
  },
  {
    id: "agent-email-bulk",
    group: "Agent",
    label: "Agent 群发邮件 6 人",
    expect: "EML-99 群发转复核",
    expectKind: "REVIEW",
    subject: { username: "assistant-agent", department: "TOOL", clearance: 3, region: "CN", title: "agent", trust: "high" },
    resourceType: "EMAIL",
    resource: { type: "EMAIL", recipientCount: 6 },
    action: "SEND",
  },
  {
    id: "agent-email-lowtrust",
    group: "Agent",
    label: "低信任 Agent 群发 4 人",
    expect: "EML-70 低信任群发限制",
    expectKind: "DENY",
    subject: { username: "newbie-agent", department: "TOOL", clearance: 2, region: "CN", title: "agent", trust: "low" },
    resourceType: "EMAIL",
    resource: { type: "EMAIL", recipientCount: 4 },
    action: "SEND",
  },
];

export default function SimulatorPanel() {
  const { token, user } = useAuth();

  // 主体属性默认取当前登录者，可随意改着玩——改完立刻能看到裁决翻转
  const [username, setUsername] = useState(user?.username ?? "");
  const [department, setDepartment] = useState(user?.department ?? "ENG");
  const [clearance, setClearance] = useState(user?.clearance ?? 3);
  const [region, setRegion] = useState(user?.region ?? "CN");
  const [title, setTitle] = useState(user?.title ?? "engineer");
  // Agent 域场景会用到信任等级（低信任有更严的群发限制）
  const [trust, setTrust] = useState("high");

  // 顶栏切换身份后（user 变化），主体属性跟随新主体——避免停留在旧账号的残留属性
  useEffect(() => {
    if (!user) return;
    setUsername(user.username ?? "");
    setDepartment(user.department ?? "ENG");
    setClearance(user.clearance ?? 3);
    setRegion(user.region ?? "CN");
    setTitle(user.title ?? "engineer");
  }, [user]);

  // 资源属性
  const [resourceType, setResourceType] = useState("DOCUMENT");
  const [owner, setOwner] = useState("alice");
  const [resDept, setResDept] = useState("ENG");
  const [classification, setClassification] = useState("INTERNAL");
  const [status, setStatus] = useState("DRAFT");
  // 扩展属性：键值对行编辑器，合并进 resource.attributes（金额 / 外链域名 / 代码内容等）
  const [extraRows, setExtraRows] = useState<KvRow[]>([]);
  const kvId = useRef(0);
  const [action, setAction] = useState("READ");

  // 环境属性（默认取当前小时，可手工调到 20 试非工作时间删文档）
  const [envHour, setEnvHour] = useState(new Date().getHours());

  const [result, setResult] = useState<DecisionResponse | null>(null);
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);

  function addExtraRow(key = "", value = "") {
    setExtraRows((rows) => [...rows, { id: kvId.current++, key, value }]);
  }

  function updateExtraRow(id: number, patch: Partial<KvRow>) {
    setExtraRows((rows) => rows.map((r) => (r.id === id ? { ...r, ...patch } : r)));
  }

  function removeExtraRow(id: number) {
    setExtraRows((rows) => rows.filter((r) => r.id !== id));
  }

  /** 快捷下拉：已存在的键聚焦到其行，否则新增一行。 */
  function addQuickKey(key: string) {
    if (!key) return;
    const existing = extraRows.find((r) => r.key.trim() === key);
    if (existing) {
      setExtraRows((rows) =>
        rows.map((r) => (r.id === existing.id ? { ...r, focus: true } : r)),
      );
      return;
    }
    addExtraRow(key, "");
  }

  /** 行编辑器 → 属性包（数字/布尔自动转换）。 */
  function rowsToAttrs(): Record<string, unknown> {
    const attrs: Record<string, unknown> = {};
    for (const r of extraRows) {
      const k = r.key.trim();
      if (!k) continue;
      attrs[k] = inferValue(r.value);
    }
    return attrs;
  }

  /** 点选预设场景：回填所有表单字段（供继续修改）并立即发起裁决。 */
  function applyScenario(sc: Scenario) {
    setUsername(String(sc.subject.username ?? ""));
    setDepartment(String(sc.subject.department ?? ""));
    setClearance(Number(sc.subject.clearance ?? 3));
    setRegion(String(sc.subject.region ?? "CN"));
    setTitle(String(sc.subject.title ?? "engineer"));
    setTrust(String(sc.subject.trust ?? "high"));
    setResourceType(sc.resourceType);
    setOwner(String(sc.resource.owner ?? ""));
    setResDept(String(sc.resource.department ?? ""));
    setClassification(String(sc.resource.classification ?? "INTERNAL"));
    setStatus(String(sc.resource.status ?? "DRAFT"));
    setAction(sc.action);
    if (sc.envHour !== undefined) setEnvHour(sc.envHour);
    // 表单固定字段之外的自定义属性（amount / urlDomain / code …）落到键值对编辑器里
    const {
      type: _t,
      owner: _o,
      department: _d,
      classification: _c,
      requiredClearance: _r,
      status: _s,
      ...extra
    } = sc.resource;
    setExtraRows(
      Object.entries(extra).map(([k, v], i) => ({ id: kvId.current++, key: k, value: String(v) })),
    );
    setResult(null);
    setErr("");
    void run(sc);
  }

  async function run(sc?: Scenario) {
    if (!token) return;
    setErr("");
    setBusy(true);
    try {
      const body: DecisionRequest = {
        subject: sc
          ? { ...sc.subject }
          : {
              username,
              department,
              clearance,
              region,
              title,
              ...(trust ? { trust } : {}),
            },
        resource: {
          type: sc ? sc.resourceType : resourceType,
          attributes: sc
            ? { ...sc.resource }
            : {
                type: resourceType,
                owner,
                department: resDept,
                classification,
                requiredClearance: REQUIRED[classification] ?? 1,
                status,
                ...rowsToAttrs(),
              },
        },
        action: sc ? sc.action : action,
        environment: { hour: sc ? sc.envHour ?? new Date().getHours() : envHour },
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
        <h2>预设场景（点选即演示）</h2>
        <p className="sub">
          一条条手填太累？直接点场景：自动填充属性包并立即问 PDP。覆盖文档 / 交易 / Agent 三个域，
          三种颜色对应三种效果——绿=放行，红=拒绝，琥珀=转人工复核。
        </p>
        {(["文档", "交易", "Agent"] as const).map((g) => (
          <div className="scenario-group" key={g}>
            <span className="scenario-group-label">{g}</span>
            <div className="scenario-grid">
              {SCENARIOS.filter((s) => s.group === g).map((sc) => (
                <button
                  key={sc.id}
                  className="scenario"
                  onClick={() => applyScenario(sc)}
                  disabled={busy}
                >
                  <span className="scenario-label">{sc.label}</span>
                  <span
                    className={`scenario-expect ${
                      sc.expectKind === "PERMIT"
                        ? "exp-permit"
                        : sc.expectKind === "DENY"
                          ? "exp-deny"
                          : "exp-review"
                    }`}
                  >
                    {sc.expect}
                  </span>
                </button>
              ))}
            </div>
          </div>
        ))}
      </div>

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
            <label>trust（Agent 域用）</label>
            <select value={trust} onChange={(e) => setTrust(e.target.value)}>
              <option value="high">high</option>
              <option value="medium">medium</option>
              <option value="low">low</option>
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
        <div className="field" style={{ marginTop: 4 }}>
          <label>
            扩展属性（键值对，合并进 resource.attributes —— 金额、外链域名、代码内容等）
          </label>
          <div className="kv-editor">
            {extraRows.length === 0 ? (
              <div className="kv-empty">
                暂无扩展属性。点「+ 添加属性」或从快捷下拉选择一个键（如 amount / urlDomain / code）。
              </div>
            ) : (
              extraRows.map((row) => (
                <div className="kv-row" key={row.id}>
                  <input
                    className="kv-key"
                    placeholder="属性键"
                    value={row.key}
                    onChange={(e) => updateExtraRow(row.id, { key: e.target.value, focus: false })}
                  />
                  <span className="kv-eq">=</span>
                  <input
                    className="kv-value"
                    placeholder="值（数字 / 文本 / true|false）"
                    value={row.value}
                    onChange={(e) => updateExtraRow(row.id, { value: e.target.value, focus: false })}
                    ref={(el) => {
                      if (el && row.focus) {
                        el.focus();
                        updateExtraRow(row.id, { focus: false });
                      }
                    }}
                  />
                  <button
                    className="kv-del"
                    onClick={() => removeExtraRow(row.id)}
                    title="删除该属性"
                  >
                    ×
                  </button>
                </div>
              ))
            )}
          </div>
          <div className="kv-actions">
            <button className="btn btn-sm ghost" onClick={() => addExtraRow()} disabled={busy}>
              + 添加属性
            </button>
            <select
              className="kv-quick"
              value=""
              onChange={(e) => addQuickKey(e.target.value)}
              disabled={busy}
            >
              <option value="">快捷添加…</option>
              {QUICK_KEYS.map((k) => (
                <option key={k} value={k}>
                  {k}
                </option>
              ))}
            </select>
          </div>
        </div>

        <div className="row" style={{ marginTop: 6 }}>
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

        <AttrBar label="本次输入：" attrs={{ hour: envHour }} kind="env" />

        <button className="btn" style={{ marginTop: 10 }} onClick={() => run()} disabled={busy}>
          {busy ? "裁决中…" : "问 PDP：能不能" + action + "？"}
        </button>

        {err && <div className="err" style={{ marginTop: 12 }}>{err}</div>}

        {result && (
          <>
            <div
              className={`verdict ${
                result.effect === "REVIEW" ? "review" : result.permitted ? "allow" : "deny"
              }`}
            >
              {result.effect === "REVIEW"
                ? "REVIEW 转人工复核"
                : result.permitted
                  ? "PERMIT 允许"
                  : "DENY 拒绝"}
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
