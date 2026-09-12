"use client";

import { clearanceLabel } from "@/lib/clearance";

/** 把属性包渲染成一排小徽章，属性是 ABAC 的核心概念，值得单独占一行。 */
export function AttrBar({
  label,
  attrs,
  kind,
}: {
  label: string;
  attrs: Record<string, unknown> | null | undefined;
  kind?: "sub" | "res" | "env";
}) {
  const entries = Object.entries(attrs ?? {}).filter(
    ([, v]) => v !== null && v !== undefined && v !== "",
  );
  return (
    <div className="attrbar">
      <span className="label">{label}</span>
      {entries.length === 0 ? (
        <span className="label">（无）</span>
      ) : (
        entries.map(([k, v]) => (
          <span key={k} className={kind ? `attr ${kind}` : "attr"}>
            {k}={String(v)}
            {k === "clearance" ? `（${clearanceLabel(v as number)}）` : ""}
          </span>
        ))
      )}
    </div>
  );
}
