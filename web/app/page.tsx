"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { AttrBar } from "@/components/AttrBar";
import PoliciesPanel from "@/components/PoliciesPanel";
import SimulatorPanel from "@/components/SimulatorPanel";
import DocumentsPanel from "@/components/DocumentsPanel";
import UsersPanel from "@/components/UsersPanel";
import AuditPanel from "@/components/AuditPanel";
import GlossaryPanel from "@/components/GlossaryPanel";
import ArchitecturePanel from "@/components/ArchitecturePanel";
import TradesPanel from "@/components/TradesPanel";
import AgentPanel from "@/components/AgentPanel";

type Tab = "policies" | "simulator" | "documents" | "users" | "audit" | "dict" | "trades" | "agent" | "arch";

type TabGroup = "demo" | "biz" | "mgmt" | "ref";

const TAB_GROUPS: { key: TabGroup; label: string; tabs: { key: Tab; label: string }[] }[] = [
  {
    key: "demo",
    label: "演示",
    tabs: [{ key: "simulator", label: "裁决模拟" }],
  },
  {
    key: "biz",
    label: "业务",
    tabs: [
      { key: "trades", label: "交易风控" },
      { key: "agent", label: "Agent 校验" },
      { key: "documents", label: "文档" },
    ],
  },
  {
    key: "mgmt",
    label: "管理",
    tabs: [
      { key: "policies", label: "策略" },
      { key: "users", label: "用户属性" },
      { key: "audit", label: "审计" },
    ],
  },
  {
    key: "ref",
    label: "参考",
    tabs: [
      { key: "dict", label: "字典" },
      { key: "arch", label: "架构" },
    ],
  },
];

export default function Home() {
  const { token, user, ready, logout } = useAuth();
  const router = useRouter();
  const [tab, setTab] = useState<Tab>("simulator");

  useEffect(() => {
    if (ready && !token) router.replace("/login");
  }, [ready, token, router]);

  // 业务面板的「去策略管理修改」入口：跨组件切换到策略 Tab
  useEffect(() => {
    const h = (e: Event) => setTab((e as CustomEvent<string>).detail as Tab);
    window.addEventListener("go-tab", h);
    return () => window.removeEventListener("go-tab", h);
  }, []);

  if (!ready) return <div className="center">加载中…</div>;
  if (!token) return null;

  return (
    <div className="app">
      <div className="topbar">
        <div className="brand">
          <span className="brand-mark">AB</span>
          <span className="brand-text">
            <b>spring-abac 控制台</b>
            <span className="brand-sub">ABAC 策略演示 · 8 微服务</span>
          </span>
        </div>
        <div className="who">
          <span className="who-line">
            当前主体 <b>{user?.username ?? "…"}</b>
            {user?.title && <span className="chip">{user.title}</span>}
            {user?.department && <span className="chip dim">{user.department}</span>}
          </span>
          <button className="link" onClick={logout}>
            退出
          </button>
        </div>
      </div>

      <div className="card" style={{ padding: "12px 16px" }}>
        <div style={{ fontSize: 12, color: "var(--muted)" }}>
          这些属性随每个请求透传给 PDP，是所有裁决的输入——改属性即改权限
        </div>
        <AttrBar
          label="subject："
          kind="sub"
          attrs={{
            username: user?.username,
            department: user?.department,
            clearance: user?.clearance,
            region: user?.region,
            title: user?.title,
          }}
        />
      </div>

      <nav className="tabs" aria-label="功能分区">
        {TAB_GROUPS.map((g) => (
          <div className="tab-group" key={g.key}>
            <span className="tab-group-label">{g.label}</span>
            {g.tabs.map((t) => (
              <button
                key={t.key}
                className={tab === t.key ? "active" : ""}
                onClick={() => setTab(t.key)}
              >
                {t.label}
              </button>
            ))}
          </div>
        ))}
      </nav>

      {tab === "simulator" && <SimulatorPanel />}
      {tab === "trades" && <TradesPanel />}
      {tab === "agent" && <AgentPanel />}
      {tab === "documents" && <DocumentsPanel />}
      {tab === "policies" && <PoliciesPanel />}
      {tab === "users" && <UsersPanel />}
      {tab === "audit" && <AuditPanel />}
      {tab === "dict" && <GlossaryPanel />}
      {tab === "arch" && <ArchitecturePanel />}
    </div>
  );
}
