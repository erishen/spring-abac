"use client";

import { useCallback, useEffect, useState } from "react";
import {
  agentSession,
  approveAgentReview,
  checkTool,
  listAgentReviews,
  listPolicies,
  myTools,
  rejectAgentReview,
} from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { GotoPoliciesLink } from "./GotoPoliciesLink";
import type {
  AgentReviewTask,
  PolicyDto,
  SessionStats,
  ToolCallRequest,
  ToolExecution,
  ToolResult,
} from "@/lib/types";

const fmtTime = (ts: number) =>
  new Date(ts).toLocaleString("zh-CN", { hour12: false });

type ToolKind = "WEB:FETCH" | "EMAIL:SEND" | "PAYMENT:TRANSFER" | "FS:DELETE" | "CODE:EXECUTE";

const TOOLS: { key: ToolKind; label: string }[] = [
  { key: "WEB:FETCH", label: "WEB / FETCH 外联网页" },
  { key: "EMAIL:SEND", label: "EMAIL / SEND 发送邮件" },
  { key: "PAYMENT:TRANSFER", label: "PAYMENT / TRANSFER 转账" },
  { key: "FS:DELETE", label: "FS / DELETE 删除文件" },
  { key: "CODE:EXECUTE", label: "CODE / EXECUTE 执行代码" },
];

const HINTS: Record<ToolKind, string> = {
  "WEB:FETCH": "白名单域名 example.com / api.example.com / cnb.example.com；其他域名被 P-95 拒绝；同会话外联超 10 次被 P-92 拒绝",
  "EMAIL:SEND": "收件人 > 5 → P-99 复核；低信任 Agent > 3 → P-70 拒绝；同会话发信超 20 → P-78 拒绝",
  "PAYMENT:TRANSFER": "金额 > 10000 → P-90 复核",
  "FS:DELETE": "路径不在 /workspace 内 → P-85 拒绝",
  "CODE:EXECUTE": "含 rm -rf（危险删除命令）→ P-80 拒绝；含 ProcessBuild（进程启动）→ P-75 复核",
};

/**
 * AI Agent 前置校验控制台：模拟 Agent 在调用工具前提交校验请求，
 * 走「网关 → agent-service → PDP」完整链路。工具级策略 + 会话次数累计 + REVIEW 复核。
 */
export default function AgentPanel() {
  const { token, user } = useAuth();
  const [agentId, setAgentId] = useState("research-agent");
  const [trust, setTrust] = useState<"high" | "medium" | "low">("high");
  const [session, setSession] = useState("sess-demo");
  const [tool, setTool] = useState<ToolKind>("WEB:FETCH");
  const [url, setUrl] = useState("https://example.com/docs");
  const [recipientCount, setRecipientCount] = useState(1);
  const [amount, setAmount] = useState(1000);
  const [path, setPath] = useState("/workspace/report.txt");
  const [code, setCode] = useState("echo 'hello'");
  const [submitting, setSubmitting] = useState(false);
  const [result, setResult] = useState<ToolResult | null>(null);
  const [executions, setExecutions] = useState<ToolExecution[]>([]);
  const [stats, setStats] = useState<SessionStats | null>(null);
  const [reviews, setReviews] = useState<AgentReviewTask[]>([]);
  const [toolPolicies, setToolPolicies] = useState<PolicyDto[]>([]);
  const [err, setErr] = useState("");
  const [ok, setOk] = useState("");

  const canReview = user?.title === "manager" || user?.title === "admin";

  const refresh = useCallback(async () => {
    if (!token) return;
    try {
      const [t, s, r, p] = await Promise.all([
        myTools(token),
        agentSession(session, token),
        listAgentReviews(token),
        listPolicies(token),
      ]);
      setExecutions(t);
      setStats(s);
      setReviews(r);
      // TOOL 域由 WEB / EMAIL / PAYMENT / FS / CODE / TOOL 六类资源组成，全部展示
      const TOOL_TYPES = ["WEB", "EMAIL", "PAYMENT", "FS", "CODE", "TOOL"];
      setToolPolicies(
        p
          .filter((x) => TOOL_TYPES.includes((x.resourceType ?? "").toUpperCase()))
          .sort((a, b) => b.priority - a.priority),
      );
    } catch {
      // 列表失败不阻断面板使用
    }
  }, [token, session]);

  useEffect(() => {
    if (token) void refresh();
  }, [token, refresh]);

  const buildParams = (): Record<string, unknown> => {
    const [toolType, operation] = tool.split(":") as [string, string];
    void operation;
    switch (toolType) {
      case "WEB":
        return { url };
      case "EMAIL":
        return { recipientCount: Number(recipientCount) };
      case "PAYMENT":
        return { amount: Number(amount) };
      case "FS":
        return { path };
      case "CODE":
        return { code };
      default:
        return {};
    }
  };

  const submit = async () => {
    if (!token) return;
    setSubmitting(true);
    setErr("");
    setOk("");
    setResult(null);
    const [toolType, operation] = tool.split(":") as [string, string];
    const body: ToolCallRequest = {
      agentId,
      trust,
      toolType,
      operation,
      params: buildParams(),
    };
    try {
      const r = await checkTool(body, session, token);
      setResult(r);
      if (r.effect === "PERMIT") {
        setOk(`工具调用已放行（${r.policyName ?? "PERMIT"}）`);
      } else if (r.effect === "REVIEW") {
        setOk(`已转人工复核 #${r.reviewId}，等待 manager/admin 审批`);
      } else {
        setErr(r.reason ?? "被 Agent 策略拒绝");
      }
      await refresh();
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setSubmitting(false);
    }
  };

  const decide = async (id: number, approve: boolean) => {
    if (!token) return;
    setErr("");
    setOk("");
    try {
      if (approve) {
        await approveAgentReview(id, token);
        setOk(`已批准 #${id}，工具调用已执行并计入会话统计`);
      } else {
        await rejectAgentReview(id, token);
        setOk(`已拒绝 #${id}`);
      }
      await refresh();
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  };

  return (
    <div className="stack">
      <div className="card">
        <h3>AI Agent 前置校验（agent-service + ABAC PDP）</h3>
        <p className="sub">
          模拟 Agent 调用工具前的门禁：请求带 Agent 身份（agentId/trust）与工具参数，
          agent-service 注入会话累计后调 ABAC 裁决——PERMIT 执行 / DENY 拒绝 / REVIEW 转人工复核。
        </p>
        <div className="row">
          <div className="field">
            <label>Agent</label>
            <select value={agentId} onChange={(e) => setAgentId(e.target.value)}>
              <option value="research-agent">research-agent</option>
              <option value="coder-agent">coder-agent</option>
              <option value="assistant-agent">assistant-agent</option>
            </select>
          </div>
          <div className="field">
            <label>信任等级</label>
            <select value={trust} onChange={(e) => setTrust(e.target.value as typeof trust)}>
              <option value="high">high</option>
              <option value="medium">medium</option>
              <option value="low">low</option>
            </select>
          </div>
          <div className="field">
            <label>会话</label>
            <input value={session} onChange={(e) => setSession(e.target.value)} />
          </div>
          <div className="field">
            <label>工具</label>
            <select value={tool} onChange={(e) => setTool(e.target.value as ToolKind)}>
              {TOOLS.map((t) => (
                <option key={t.key} value={t.key}>{t.label}</option>
              ))}
            </select>
          </div>
        </div>

        <div className="row">
          {tool.startsWith("WEB") && (
            <div className="field" style={{ flex: 2 }}>
              <label>目标 URL</label>
              <input value={url} onChange={(e) => setUrl(e.target.value)} />
            </div>
          )}
          {tool.startsWith("EMAIL") && (
            <div className="field">
              <label>收件人数</label>
              <input
                type="number"
                min={1}
                value={recipientCount}
                onChange={(e) => setRecipientCount(Number(e.target.value))}
              />
            </div>
          )}
          {tool.startsWith("PAYMENT") && (
            <div className="field">
              <label>转账金额</label>
              <input
                type="number"
                min={1}
                value={amount}
                onChange={(e) => setAmount(Number(e.target.value))}
              />
            </div>
          )}
          {tool.startsWith("FS") && (
            <div className="field" style={{ flex: 2 }}>
              <label>删除路径</label>
              <input value={path} onChange={(e) => setPath(e.target.value)} />
            </div>
          )}
          {tool.startsWith("CODE") && (
            <div className="field" style={{ flex: 2 }}>
              <label>代码片段</label>
              <input value={code} onChange={(e) => setCode(e.target.value)} />
            </div>
          )}
        </div>
        <div className="hint">{HINTS[tool]}</div>
        <button className="btn" disabled={submitting} onClick={submit}>
          {submitting ? "校验中…" : "提交工具调用校验"}
        </button>

        {result && (
          <div
            className={result.effect === "PERMIT" ? "ok" : result.effect === "REVIEW" ? "warn" : "err"}
            style={{ marginTop: 12 }}
          >
            <strong>裁决：{result.effect}</strong>
            {result.policyName && <span>（{result.policyName}）</span>}
            {result.reason && <span> —— {result.reason}</span>}
            {result.message && <span> —— {result.message}</span>}
          </div>
        )}
        {err && <div className="err" style={{ marginTop: 12 }}>{err}</div>}
        {ok && <div className="ok" style={{ marginTop: 12 }}>{ok}</div>}
      </div>

      <div className="card">
        <h3>当前生效的工具策略（TOOL 域）</h3>
        <p className="sub">
          WEB / EMAIL / PAYMENT / FS / CODE 五类工具各自的 DENY、REVIEW 与 PERMIT 兜底，
          加上 TOOL/LIST、TOOL/EXECUTE 网关兜底。按优先级从高到低求值，DENY 短路（deny-override），
          REVIEW 转人工复核，都不中才落到 PERMIT 兜底。
        </p>
        {toolPolicies.length === 0 ? (
          <div className="empty-state">暂无 TOOL 策略（可在「策略」Tab 录入）</div>
        ) : (
          <table className="pol-table">
            <thead>
              <tr>
                <th>优先级</th>
                <th>效果</th>
                <th>策略</th>
                <th>资源</th>
                <th>动作</th>
                <th>条件</th>
                <th>说明</th>
              </tr>
            </thead>
            <tbody>
              {toolPolicies.map((p) => (
                <tr key={p.id}>
                  <td>{p.priority}</td>
                  <td>
                    {p.effect === "DENY" && <span className="err">DENY</span>}
                    {p.effect === "REVIEW" && <span className="warn">REVIEW</span>}
                    {p.effect === "PERMIT" && <span className="ok">PERMIT</span>}
                  </td>
                  <td>{p.name}</td>
                  <td>{p.resourceType ?? "*"}</td>
                  <td>{p.action ?? "*"}</td>
                  <td className="cond">{p.condition ?? "-"}</td>
                  <td className="sub">{p.description ?? ""}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <GotoPoliciesLink />
      </div>

      <div className="card">
        <h3>会话统计（内存状态，重启清零）</h3>
        <p className="sub">
          同会话的工具调用次数作为资源属性（sessionFetchCount 等）参与裁决：
          外联超 10 次 → P-92 拒绝，发信超 20 封 → P-78 拒绝。
        </p>
        {stats && (
          <table className="cust-table">
            <thead>
              <tr>
                <th>会话</th>
                <th>外联</th>
                <th>发信</th>
                <th>转账</th>
                <th>执行代码</th>
                <th>删除</th>
                <th>外联上限</th>
                <th>发信上限</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td>{stats.sessionId}</td>
                <td>{stats.fetchCount}/{stats.limitFetch}</td>
                <td>{stats.sendCount}/{stats.limitSend}</td>
                <td>{stats.transferCount}</td>
                <td>{stats.executeCount}</td>
                <td>{stats.deleteCount}</td>
                <td>{stats.limitFetch}</td>
                <td>{stats.limitSend}</td>
              </tr>
            </tbody>
          </table>
        )}
        <h4 style={{ marginTop: 16 }}>最近工具调用</h4>
        {executions.length === 0 ? (
          <div className="empty-state">暂无放行记录</div>
        ) : (
          <table className="cust-table">
            <thead>
              <tr>
                <th>#</th>
                <th>用户</th>
                <th>Agent</th>
                <th>工具</th>
                <th>摘要</th>
                <th>裁决</th>
                <th>命中策略</th>
                <th>时间</th>
              </tr>
            </thead>
            <tbody>
              {executions.slice(-8).reverse().map((t) => (
                <tr key={t.id}>
                  <td>{t.id}</td>
                  <td>{t.username}</td>
                  <td>{t.agentId}</td>
                  <td>{t.toolType}/{t.operation}</td>
                  <td className="sub">{t.summary}</td>
                  <td>{t.effect}</td>
                  <td>{t.policyName}</td>
                  <td>{fmtTime(t.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div className="card">
        <h3>Agent 复核队列（REVIEW 第三态）</h3>
        <p className="sub">
          群发邮件、大额转账、疑似高危代码等转人工确认；批准后工具调用才执行并计入会话统计。
          {!canReview && " 当前账号非 manager/admin，仅可查看。"}
        </p>
        {reviews.length === 0 ? (
          <div className="empty-state">暂无复核任务</div>
        ) : (
          <table className="pol-table">
            <thead>
              <tr>
                <th>#</th>
                <th>用户</th>
                <th>Agent</th>
                <th>工具</th>
                <th>摘要</th>
                <th>命中策略</th>
                <th>状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {reviews.map((t) => (
                <tr key={t.id}>
                  <td>{t.id}</td>
                  <td>{t.username}</td>
                  <td>{t.agentId}</td>
                  <td>{t.toolType}/{t.operation}</td>
                  <td className="sub">{t.summary}</td>
                  <td>{t.policyName}</td>
                  <td>
                    {t.status === "PENDING" && <span className="warn">待复核</span>}
                    {t.status === "APPROVED" && <span className="ok">已批准（{t.decidedBy ?? "-"}）</span>}
                    {t.status === "REJECTED" && <span className="err">已拒绝（{t.decidedBy ?? "-"}）</span>}
                  </td>
                  <td>
                    {t.status === "PENDING" && canReview ? (
                      <span className="row" style={{ gap: 8 }}>
                        <button className="btn" onClick={() => decide(t.id, true)}>批准</button>
                        <button className="btn ghost" onClick={() => decide(t.id, false)}>拒绝</button>
                      </span>
                    ) : (
                      "-"
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
