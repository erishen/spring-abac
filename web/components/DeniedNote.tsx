"use client";

import { useAuth } from "@/lib/auth";
import { accountPassword } from "@/lib/demo-accounts";

/** 权限不足说明卡：明确展示是哪个 ABAC 策略挡住、缺什么主体属性、换谁可以看。 */
export function DeniedNote({
  policyRefs,
  reason,
  suggest,
  switchTo,
}: {
  /** 命中的策略编号与语义，如 "AUD-40 仅管理员可查看审计日志"。 */
  policyRefs: string;
  /** 当前主体为什么被挡，如 "title=sales 未命中任何 PERMIT，触发默认拒绝（default deny）"。 */
  reason: string;
  /** 谁能看到，建议切换的身份。 */
  suggest: string;
  /** 可选：一键切换的演示账号（右上角切换菜单里的名字）。 */
  switchTo?: string;
}) {
  const { login } = useAuth();
  const pw = switchTo ? accountPassword(switchTo) : undefined;

  return (
    <div className="denied-note" role="alert">
      <div className="denied-title">权限不足：请求被 ABAC 策略挡住</div>
      <div className="denied-line">
        <b>命中面</b>
        {policyRefs}
      </div>
      <div className="denied-line">
        <b>原因</b>
        {reason}
      </div>
      <div className="denied-line">
        <b>建议</b>
        {suggest}
      </div>
      {switchTo && pw && (
        <button className="btn btn-sm denied-switch" onClick={() => login(switchTo, pw)}>
          一键切换 {switchTo} 查看
        </button>
      )}
    </div>
  );
}
