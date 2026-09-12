/** 演示账号（密码在登录页公开提示，仅用于本演示系统）。 */
export const DEMO_ACCOUNTS: { username: string; password: string; tag: string; note: string }[] = [
  { username: "admin", password: "admin123", tag: "EXEC · 绝密 5 · CN", note: "全通" },
  { username: "carol", password: "carol123", tag: "ENG · 机密 4 · US", note: "境外地区" },
  { username: "alice", password: "alice123", tag: "ENG · 受限 3 · CN", note: "普通工程师" },
  { username: "bob", password: "bob123", tag: "SALES · 内部 2 · CN", note: "跨部门低密级" },
];

export function accountPassword(username: string): string | undefined {
  return DEMO_ACCOUNTS.find((a) => a.username === username)?.password;
}
