"use client";

import { useEffect, useRef, useState } from "react";
import { useAuth } from "@/lib/auth";
import { clearanceLabel } from "@/lib/clearance";

/** 演示账号：属性差异明显，切换即换主体（token + 主体属性），配合裁决模拟/业务面板看同策略的不同结果。 */
const DEMO_ACCOUNTS: { username: string; password: string; tag: string; note: string }[] = [
  { username: "admin", password: "admin123", tag: "EXEC · 绝密 5 · CN", note: "全通" },
  { username: "carol", password: "carol123", tag: "ENG · 机密 4 · US", note: "境外地区" },
  { username: "alice", password: "alice123", tag: "ENG · 受限 3 · CN", note: "普通工程师" },
  { username: "bob", password: "bob123", tag: "SALES · 内部 2 · CN", note: "跨部门低密级" },
];

/** 顶栏当前主体：完整属性 + 演示身份一键切换。 */
export function SubjectSwitcher() {
  const { user, login, logout } = useAuth();
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement>(null);

  // 点击外部收起菜单
  useEffect(() => {
    if (!open) return;
    const h = (e: MouseEvent) => {
      if (box.current && !box.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", h);
    return () => document.removeEventListener("mousedown", h);
  }, [open]);

  if (!user) return null;

  const cl = user.clearance ?? 0;
  const switchTo = async (u: string, p: string) => {
    setOpen(false);
    try {
      await login(u, p);
    } catch {
      // 切换失败保留当前会话
    }
  };

  return (
    <div className="who" ref={box}>
      <div className="who-identity">
        <span className="who-line">
          <b>{user.username}</b>
          {user.title && <span className="chip">{user.title}</span>}
          {user.region && <span className="chip dim">{user.region}</span>}
        </span>
        <span className={`cl-badge cl-${cl}`}>
          clearance {user.clearance} · {clearanceLabel(cl)}
        </span>
      </div>
      <div className="who-actions">
        <button className="link who-switch" onClick={() => setOpen((v) => !v)}>
          {open ? "收起 ▴" : "切换身份 ▾"}
        </button>
        <button className="link" onClick={logout}>
          退出
        </button>
      </div>
      {open && (
        <div className="switch-menu" role="menu">
          {DEMO_ACCOUNTS.map((a) => (
            <button
              key={a.username}
              className="switch-item"
              role="menuitem"
              onClick={() => switchTo(a.username, a.password)}
            >
              <span>
                <b>{a.username}</b>
                <span className="sub">{a.tag}</span>
              </span>
              <span className="sub">{a.note}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
