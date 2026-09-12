/**
 * clearance 密级档位的语言解释（1-5）。
 * 与 document-service 的密级体系对应：PUBLIC=1 / INTERNAL=2 / CONFIDENTIAL=4 / SECRET=5，
 * 3 是中间档（如 alice 的"普通工程师"），无直接对应文档密级。
 */
export const CLEARANCE_LABELS: Record<number, string> = {
  1: "公开 PUBLIC",
  2: "内部 INTERNAL",
  3: "受限（内部与机密之间）",
  4: "机密 CONFIDENTIAL",
  5: "绝密 SECRET",
};

export function clearanceLabel(c: number | null | undefined): string {
  if (c == null) return "";
  return CLEARANCE_LABELS[c] ?? `级别 ${c}`;
}
