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

type Tab = "policies" | "simulator" | "documents" | "users" | "audit";

const TABS: { key: Tab; label: string }[] = [
  { key: "simulator", label: "裁决模拟" },
  { key: "documents", label: "文档" },
  { key: "policies", label: "策略" },
  { key: "users", label: "用户属性" },
  { key: "audit", label: "审计" },
];

export default function Home() {
  const { token, user, ready, logout } = useAuth();
  const router = useRouter();
  const [tab, setTab] = useState<Tab>("simulator");

  useEffect(() => {
    if (ready && !token) router.replace("/login");
  }, [ready, token, router]);

  if (!ready) return <div className="center">加载中…</div>;
  if (!token) return null;

  return (
    <div className="app">
      <div className="topbar">
        <div className="brand">
          <span className="dot" />
          spring-abac 控制台
        </div>
        <div className="who">
          当前主体 <b>{user?.username ?? "…"}</b>
          <button className="link" style={{ marginLeft: 12 }} onClick={logout}>
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

      <div className="tabs">
        {TABS.map((t) => (
          <button
            key={t.key}
            className={tab === t.key ? "active" : ""}
            onClick={() => setTab(t.key)}
          >
            {t.label}
          </button>
        ))}
      </div>

      {tab === "simulator" && <SimulatorPanel />}
      {tab === "documents" && <DocumentsPanel />}
      {tab === "policies" && <PoliciesPanel />}
      {tab === "users" && <UsersPanel />}
      {tab === "audit" && <AuditPanel />}
    </div>
  );
}
