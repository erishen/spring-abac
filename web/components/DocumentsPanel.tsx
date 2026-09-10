"use client";

import { useCallback, useEffect, useState } from "react";
import {
  createDocument,
  deleteDocument,
  listDocuments,
  publishDocument,
  updateDocument,
} from "@/lib/api";
import { useAuth } from "@/lib/auth";
import type { DocumentDto, DocumentPage } from "@/lib/types";

const CLASSIFICATIONS = ["PUBLIC", "INTERNAL", "CONFIDENTIAL", "SECRET"];
const STATUSES = ["DRAFT", "PUBLISHED", "ARCHIVED"];

export default function DocumentsPanel() {
  const { token } = useAuth();
  const [page, setPage] = useState<DocumentPage | null>(null);
  const [q, setQ] = useState("");
  const [p, setP] = useState(0);
  const [size, setSize] = useState(10);
  const [err, setErr] = useState("");
  const [ok, setOk] = useState("");

  const [title, setTitle] = useState("");
  const [content, setContent] = useState("");
  const [department, setDepartment] = useState("ENG");
  const [classification, setClassification] = useState("INTERNAL");

  const load = useCallback(async () => {
    if (!token) return;
    try {
      setPage(await listDocuments(token, { q: q || undefined, page: p, size }));
      setErr("");
    } catch (e) {
      setErr(e instanceof Error ? e.message : "加载文档失败");
    }
  }, [token, q, p, size]);

  useEffect(() => {
    void load();
  }, [load]);

  async function submit() {
    if (!token) return;
    setErr("");
    setOk("");
    try {
      await createDocument(token, {
        title,
        content,
        department,
        classification,
        status: "DRAFT",
      });
      setOk("文档已创建");
      setTitle("");
      setContent("");
      await load();
    } catch (e) {
      setErr(e instanceof Error ? e.message : "创建失败");
    }
  }

  async function publish(id: number) {
    if (!token) return;
    setErr("");
    setOk("");
    try {
      await publishDocument(token, id);
      setOk("已发布");
      await load();
    } catch (e) {
      setErr(e instanceof Error ? e.message : "发布失败");
    }
  }

  async function remove(id: number) {
    if (!token) return;
    setErr("");
    setOk("");
    try {
      await deleteDocument(token, id);
      setOk("已删除");
      await load();
    } catch (e) {
      setErr(e instanceof Error ? e.message : "删除失败");
    }
  }

  return (
    <div>
      <div className="card">
        <h2>受保护文档</h2>
        <p className="sub">
          列表不是"全量返回再前端过滤"：网关 PEP 先裁一次，文档服务再按每行的
          owner / department / classification 属性向 PDP 批量裁决，只放行读得到的行。
          换账号登录看到的行数会不一样。
        </p>

        <div className="row">
          <div className="field">
            <label>搜索（标题 / 内容 / 作者）</label>
            <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="如：OKR" />
          </div>
        </div>
        <div style={{ display: "flex", gap: 8, marginTop: 8 }}>
          <button className="btn ghost" onClick={() => void load()}>
            搜索
          </button>
        </div>

        {err && <div className="err" style={{ marginTop: 12 }}>{err}</div>}
        {ok && <div className="ok" style={{ marginTop: 12 }}>{ok}</div>}
      </div>

      <div className="card">
        <h2>新建文档</h2>
        <p className="sub">作者自动取当前登录者；密级决定谁能读（CONFIDENTIAL=4，SECRET=5）。</p>
        <div className="row">
          <div className="field">
            <label>标题</label>
            <input value={title} onChange={(e) => setTitle(e.target.value)} />
          </div>
          <div className="field">
            <label>部门</label>
            <input value={department} onChange={(e) => setDepartment(e.target.value)} />
          </div>
          <div className="field">
            <label>密级</label>
            <select
              value={classification}
              onChange={(e) => setClassification(e.target.value)}
            >
              {CLASSIFICATIONS.map((c) => (
                <option key={c} value={c}>
                  {c}
                </option>
              ))}
            </select>
          </div>
        </div>
        <div className="field">
          <label>内容</label>
          <input value={content} onChange={(e) => setContent(e.target.value)} />
        </div>
        <button className="btn" onClick={submit}>
          创建（走 CREATE 策略）
        </button>
      </div>

      <div className="card">
        <h2>可见文档 {page ? `（共 ${page.totalElements} 条）` : ""}</h2>
        <table className="cust-table">
          <thead>
            <tr>
              <th>标题</th>
              <th>作者</th>
              <th>部门</th>
              <th>密级</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {(page?.content ?? []).map((d: DocumentDto) => (
              <tr key={d.id}>
                <td className="name">{d.title}</td>
                <td>{d.owner}</td>
                <td>{d.department}</td>
                <td>
                  {d.classification}
                  <span style={{ color: "var(--muted)", fontSize: 12 }}>
                    {" "}
                    (≥{d.requiredClearance})
                  </span>
                </td>
                <td>{d.status}</td>
                <td className="actions">
                  <button
                    className="btn ghost"
                    style={{ padding: "5px 9px", fontSize: 12, marginRight: 6 }}
                    onClick={() => publish(d.id)}
                  >
                    发布
                  </button>
                  <button
                    className="btn btn-danger"
                    style={{ padding: "5px 9px", fontSize: 12 }}
                    onClick={() => remove(d.id)}
                  >
                    删除
                  </button>
                </td>
              </tr>
            ))}
            {(page?.content.length ?? 0) === 0 && (
              <tr>
                <td colSpan={6} className="meta">
                  当前主体看不到任何文档（或还没播种）
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
              {[5, 10, 20, 50].map((s) => (
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
