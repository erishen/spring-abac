import type {
  AgentReviewTask,
  AuditPage,
  AuditStats,
  CreateDocumentRequest,
  CreatePolicyRequest,
  DecisionRequest,
  DecisionResponse,
  DocumentDto,
  DocumentPage,
  PolicyDto,
  ReviewTask,
  SessionStats,
  TokenResponse,
  ToolCallRequest,
  ToolExecution,
  ToolResult,
  TradeExecution,
  TradeRequest,
  TradeResult,
  TradeStats,
  UpdateDocumentRequest,
  UpdatePolicyRequest,
  UserDto,
  UserInfo,
} from "./types";

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
    this.name = "ApiError";
  }
}

interface ApiOptions {
  method?: string;
  body?: unknown;
  token?: string | null;
}

/**
 * 统一请求封装。
 * 浏览器只请求同源 /api/*，由 Next 服务端 rewrite 代理到 Spring Cloud Gateway（4110）。
 * 鉴权：有 token 时自动带 Bearer；非 2xx 抛 ApiError（含状态码与后端消息）。
 */
export async function api<T = unknown>(
  path: string,
  opts: ApiOptions = {},
): Promise<T> {
  const res = await fetch(path, {
    method: opts.method ?? "GET",
    headers: {
      "Content-Type": "application/json",
      ...(opts.token ? { Authorization: `Bearer ${opts.token}` } : {}),
    },
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
  });

  const text = await res.text();
  let data: unknown = undefined;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = text;
    }
  }

  if (!res.ok) {
    const d = data as Record<string, unknown> | undefined;
    const msg =
      (d && (d.message as string | undefined)) ||
      (d && (d.error as string | undefined)) ||
      (typeof data === "string" ? data : "") ||
      res.statusText;
    throw new ApiError(res.status, msg || `HTTP ${res.status}`);
  }
  return data as T;
}

// ---------------- 认证 ----------------

export const login = (username: string, password: string) =>
  api<TokenResponse>("/api/login", { method: "POST", body: { username, password } });

export const register = (body: {
  username: string;
  password: string;
  department?: string;
  clearance?: number;
  region?: string;
  title?: string;
}) => api<UserDto>("/api/register", { method: "POST", body });

/** 当前登录者及其主体属性（属性就是 ABAC 的判定输入）。 */
export const me = (token: string) => api<UserInfo>("/api/me", { token });

export const listUsers = (token: string) => api<UserDto[]>("/api/users", { token });

export const updateUserAttributes = (
  token: string,
  username: string,
  body: { department?: string; clearance?: number; region?: string; title?: string },
) =>
  api<UserDto>(
    `/api/users/${encodeURIComponent(username)}/attributes`,
    { method: "PUT", token, body },
  );

// ---------------- 策略（PAP）----------------

export const listPolicies = (token: string) =>
  api<PolicyDto[]>("/api/policies", { token });

export const createPolicy = (token: string, body: CreatePolicyRequest) =>
  api<PolicyDto>("/api/policies", { method: "POST", token, body });

export const updatePolicy = (token: string, id: number, body: UpdatePolicyRequest) =>
  api<PolicyDto>(`/api/policies/${id}`, { method: "PUT", token, body });

export const deletePolicy = (token: string, id: number) =>
  api<void>(`/api/policies/${id}`, { method: "DELETE", token });

// ---------------- 裁决（PDP）----------------

/** 单次裁决：传完整属性包问 PDP，返回 effect + 命中策略 + 判定轨迹。 */
export const decide = (token: string, body: DecisionRequest) =>
  api<DecisionResponse>("/api/decide", { method: "POST", token, body });

// ---------------- 文档（业务域）----------------

export const listDocuments = (
  token: string,
  opts: { q?: string; page?: number; size?: number } = {},
) => {
  const params = new URLSearchParams({
    page: String(opts.page ?? 0),
    size: String(opts.size ?? 20),
  });
  if (opts.q) params.set("q", opts.q);
  return api<DocumentPage>(`/api/documents?${params.toString()}`, { token });
};

export const getDocument = (token: string, id: number) =>
  api<DocumentDto>(`/api/documents/${id}`, { token });

export const createDocument = (token: string, body: CreateDocumentRequest) =>
  api<DocumentDto>("/api/documents", { method: "POST", token, body });

export const updateDocument = (
  token: string,
  id: number,
  body: UpdateDocumentRequest,
) => api<DocumentDto>(`/api/documents/${id}`, { method: "PUT", token, body });

export const deleteDocument = (token: string, id: number) =>
  api<void>(`/api/documents/${id}`, { method: "DELETE", token });

export const publishDocument = (token: string, id: number) =>
  api<DocumentDto>(`/api/documents/${id}/publish`, { method: "POST", token });

// ---------------- 审计 ----------------

export const listAudit = (
  token: string,
  opts: { page?: number; size?: number; decision?: string; traceId?: string; actor?: string } = {},
) => {
  const params = new URLSearchParams({
    page: String(opts.page ?? 0),
    size: String(opts.size ?? 20),
  });
  if (opts.decision) params.set("decision", opts.decision);
  if (opts.traceId) params.set("traceId", opts.traceId);
  if (opts.actor) params.set("actor", opts.actor);
  return api<AuditPage>(`/api/audit?${params.toString()}`, { token });
};

export const getAuditStats = (token: string) =>
  api<AuditStats>("/api/audit/stats", { token });

// ---------------- 交易风控（risk-service） ----------------

export const executeTrade = (body: TradeRequest, token?: string) =>
  api<TradeResult>("/api/trades", { method: "POST", body, token });

export const myTrades = (token?: string) =>
  api<TradeExecution[]>("/api/trades", { token });

export const tradeStats = (token?: string) =>
  api<TradeStats>("/api/trades/stats", { token });

export const listReviews = (token?: string) =>
  api<ReviewTask[]>("/api/trades/reviews", { token });

export const approveReview = (id: number, token?: string) =>
  api<{ effect: string }>(`/api/trades/reviews/${id}/approve`, { method: "POST", token });

export const rejectReview = (id: number, token?: string) =>
  api<{ effect: string }>(`/api/trades/reviews/${id}/reject`, { method: "POST", token });

// ---------------- AI Agent 前置校验（agent-service） ----------------

export const checkTool = (body: ToolCallRequest, session: string, token?: string) =>
  api<ToolResult>(`/api/agent/tools?session=${encodeURIComponent(session)}`, {
    method: "POST",
    body,
    token,
  });

export const myTools = (token?: string) =>
  api<ToolExecution[]>("/api/agent/tools", { token });

export const agentSession = (session: string, token?: string) =>
  api<SessionStats>(`/api/agent/session?session=${encodeURIComponent(session)}`, { token });

export const listAgentReviews = (token?: string) =>
  api<AgentReviewTask[]>("/api/agent/reviews", { token });

export const approveAgentReview = (id: number, token?: string) =>
  api<{ effect: string }>(`/api/agent/reviews/${id}/approve`, { method: "POST", token });

export const rejectAgentReview = (id: number, token?: string) =>
  api<{ effect: string }>(`/api/agent/reviews/${id}/reject`, { method: "POST", token });
