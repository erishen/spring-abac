"use client";

import { useCallback, useEffect, useState } from "react";
import {
  approveReview,
  executeTrade,
  listReviews,
  myTrades,
  rejectReview,
  tradeStats,
} from "@/lib/api";
import { useAuth } from "@/lib/auth";
import type { ReviewTask, TradeExecution, TradeRequest, TradeResult, TradeStats } from "@/lib/types";

const EMPTY: TradeRequest = {
  amount: 10000,
  channel: "MOBILE",
  region: "CN",
  instrument: "AAPL",
};

const fmt = (n: number) =>
  new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 2 }).format(n);

const fmtTime = (ts: number) =>
  new Date(ts).toLocaleString("zh-CN", { hour12: false });

/**
 * 交易风控面板：模拟下单走「网关 → risk-service → PDP」完整链路。
 * PERMIT 直接成交 / DENY 展示拦截策略与原因 / REVIEW 转人工复核（manager/admin 可批准/拒绝），
 * 批准后计入当日累计额度。
 */
export default function TradesPanel() {
  const { token, user } = useAuth();
  const [form, setForm] = useState<TradeRequest>(EMPTY);
  const [submitting, setSubmitting] = useState(false);
  const [result, setResult] = useState<TradeResult | null>(null);
  const [trades, setTrades] = useState<TradeExecution[]>([]);
  const [stats, setStats] = useState<TradeStats | null>(null);
  const [reviews, setReviews] = useState<ReviewTask[]>([]);
  const [err, setErr] = useState("");
  const [ok, setOk] = useState("");

  const canReview = user?.title === "manager" || user?.title === "admin";

  const refresh = useCallback(async () => {
    if (!token) return;
    try {
      const [t, s, r] = await Promise.all([
        myTrades(token),
        tradeStats(token),
        listReviews(token),
      ]);
      setTrades(t);
      setStats(s);
      setReviews(r);
    } catch {
      // 列表失败不阻断面板使用
    }
  }, [token]);

  useEffect(() => {
    if (token) void refresh();
  }, [token, refresh]);

  const submit = async () => {
    if (!token) return;
    setSubmitting(true);
    setErr("");
    setOk("");
    setResult(null);
    try {
      const r = await executeTrade(
        { ...form, amount: Number(form.amount) },
        token,
      );
      setResult(r);
      if (r.effect === "PERMIT") {
        setOk(`成交成功（${r.policyName ?? "PERMIT"}）`);
      } else if (r.effect === "REVIEW") {
        setOk(`已转人工复核 #${r.reviewId}，等待 manager/admin 审批`);
      } else {
        setErr(r.reason ?? "被风控规则拒绝");
      }
      await refresh();
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      setErr(msg);
      if (msg.includes("denied by risk policy") || msg.includes("风控")) {
        // 403 已被 api() 包装成 ApiError，这里直接展示 reason
      }
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
        await approveReview(id, token);
        setOk(`已批准 #${id}，金额计入该用户当日累计`);
      } else {
        await rejectReview(id, token);
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
        <h3>交易风控（risk-service + ABAC PDP）</h3>
        <p className="sub">
          模拟下单：请求经网关透传主体属性，risk-service 把当日累计注入资源属性后
          调 ABAC 裁决。PERMIT 成交 / DENY 拒绝 / REVIEW 转人工复核（第三态）。
        </p>
        <div className="row">
          <div className="field">
            <label>金额</label>
            <input
              type="number"
              min={1}
              value={form.amount}
              onChange={(e) => setForm({ ...form, amount: Number(e.target.value) })}
            />
          </div>
          <div className="field">
            <label>渠道</label>
            <select
              value={form.channel}
              onChange={(e) => setForm({ ...form, channel: e.target.value })}
            >
              <option value="MOBILE">MOBILE</option>
              <option value="APP">APP</option>
              <option value="WEB">WEB</option>
            </select>
          </div>
          <div className="field">
            <label>交易地区</label>
            <select
              value={form.region}
              onChange={(e) => setForm({ ...form, region: e.target.value })}
            >
              <option value="CN">CN</option>
              <option value="US">US</option>
              <option value="SG">SG</option>
            </select>
          </div>
          <div className="field">
            <label>标的</label>
            <input
              value={form.instrument}
              onChange={(e) => setForm({ ...form, instrument: e.target.value })}
            />
          </div>
        </div>
        <div className="hint">
          试试：金额 60000 → REVIEW（P-85）；WEB+US → DENY（P-75）；连续累计超 100000 → DENY（P-80）
        </div>
        <button className="btn" disabled={submitting} onClick={submit}>
          {submitting ? "校验中…" : "提交风控校验"}
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
        <h3>当日累计额度（内存状态，重启清零）</h3>
        <p className="sub">
          我的累计金额/次数会作为 resource.cumulativeAmount / cumulativeAfter 参与裁决，
          P-80 据此拒绝超过 100,000 的当日累计。
        </p>
        {stats && (
          <table className="cust-table">
            <thead>
              <tr>
                <th>日期</th>
                <th>用户</th>
                <th>成交次数</th>
                <th>累计金额</th>
                <th>累计上限</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td>{stats.date}</td>
                <td>{stats.username}</td>
                <td>{stats.count}</td>
                <td>{fmt(stats.amountTotal)}</td>
                <td>{fmt(stats.limitAmount)}</td>
              </tr>
            </tbody>
          </table>
        )}
        <h4 style={{ marginTop: 16 }}>最近成交</h4>
        {trades.length === 0 ? (
          <p className="sub">暂无成交记录</p>
        ) : (
          <table className="cust-table">
            <thead>
              <tr>
                <th>#</th>
                <th>金额</th>
                <th>渠道</th>
                <th>地区</th>
                <th>标的</th>
                <th>裁决</th>
                <th>命中策略</th>
                <th>时间</th>
              </tr>
            </thead>
            <tbody>
              {trades.slice(-8).reverse().map((t) => (
                <tr key={t.id}>
                  <td>{t.id}</td>
                  <td>{fmt(t.amount)}</td>
                  <td>{t.channel}</td>
                  <td>{t.region}</td>
                  <td>{t.instrument}</td>
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
        <h3>人工复核队列（REVIEW 第三态）</h3>
        <p className="sub">
          REVIEW 命中的交易不会直接放行，需 manager/admin 人工批准或拒绝；批准才计入当日累计。
          {!canReview && " 当前账号非 manager/admin，仅可查看。"}
        </p>
        {reviews.length === 0 ? (
          <p className="sub">暂无复核任务</p>
        ) : (
          <table className="pol-table">
            <thead>
              <tr>
                <th>#</th>
                <th>用户</th>
                <th>金额</th>
                <th>渠道</th>
                <th>地区</th>
                <th>标的</th>
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
                  <td>{fmt(t.amount)}</td>
                  <td>{t.channel}</td>
                  <td>{t.region}</td>
                  <td>{t.instrument}</td>
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
