"use client";

/** 业务面板策略清单卡右下角的入口：跳到「管理 → 策略」Tab 修改规则。
 *  业务面板的清单是只读镜像（PEP/PDP 视角），改规则要去 PAP（POL-35 仅 admin）。 */
export function GotoPoliciesLink() {
  const go = () => window.dispatchEvent(new CustomEvent("go-tab", { detail: "policies" }));
  return (
    <div className="goto-policies">
      <button className="link" onClick={go}>
        去策略管理修改 →
      </button>
    </div>
  );
}
