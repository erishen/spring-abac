// 前端类型定义，字段与后端 DTO 一一对应。

/** 主体属性（ABAC 的 subject）：来自 auth-service 的 User，登录时写入 JWT。 */
export interface SubjectAttrs {
  username: string;
  department: string | null;
  clearance: number | null;
  region: string | null;
  title: string | null;
}

export interface UserInfo extends SubjectAttrs {}

export interface UserDto {
  id: number;
  username: string;
  department: string | null;
  clearance: number | null;
  region: string | null;
  title: string | null;
  createdAt: number | null;
}

export interface TokenResponse {
  token: string;
}

// ---------------- 策略 ----------------

/** 策略效果：PERMIT 放行 / DENY 拒绝 / REVIEW 转人工复核（风控第三态）。 */
export type Effect = "PERMIT" | "DENY" | "REVIEW";

export interface PolicyDto {
  id: number;
  name: string;
  description: string | null;
  effect: Effect;
  resourceType: string | null;
  action: string | null;
  condition: string | null;
  priority: number;
  enabled: boolean;
  createdAt: number | null;
}

export interface CreatePolicyRequest {
  name: string;
  description?: string;
  effect: Effect;
  resourceType?: string;
  action?: string;
  condition?: string;
  priority?: number;
  enabled?: boolean;
}

export interface UpdatePolicyRequest {
  name?: string;
  description?: string;
  effect?: Effect;
  resourceType?: string;  action?: string;
  condition?: string;
  priority?: number;
  enabled?: boolean;
}

// ---------------- 裁决 ----------------

export interface ResourceRef {
  type: string;
  id?: string;
  attributes?: Record<string, unknown>;
}

export interface DecisionRequest {
  subject: Record<string, unknown>;
  resource: ResourceRef;
  action: string;
  environment?: Record<string, unknown>;
}

/** 单条策略的求值痕迹：为什么匹配/不匹配，或求值报错。 */
export interface TraceEntry {
  policyId: number | null;
  policyName: string | null;
  effect: Effect;
  matched: boolean;
  error: string | null;
}

export interface DecisionResponse {
  /** PERMIT / DENY。 */
  effect: Effect;
  permitted: boolean;
  policyId: number | null;
  policyName: string | null;
  reason: string | null;
  /** 判定过程（按优先级顺序），UI 据此渲染"为什么"。 */
  trace: TraceEntry[];
}

// ---------------- 文档 ----------------

export interface DocumentDto {
  id: number;
  title: string;
  content: string | null;
  owner: string;
  department: string;
  classification: string;
  status: string;
  requiredClearance: number;
  createdAt: number | null;
}

export interface DocumentPage {
  content: DocumentDto[];
  totalElements: number;
  number: number;
  size: number;
  totalPages: number;
}

export interface CreateDocumentRequest {
  title: string;
  content?: string;
  department?: string;
  classification?: string;
  status?: string;
}

export interface UpdateDocumentRequest {
  title?: string;
  content?: string;
  department?: string;
  classification?: string;
  status?: string;
}

// ---------------- 审计 ----------------

export interface AuditLogDto {
  id: number;
  traceId: string | null;
  actor: string;
  action: string;
  method: string;
  path: string;
  resourceId: number | null;
  decision: "ALLOW" | "DENY";
  status: number | null;
  policyId: number | null;
  policyName: string | null;
  reason: string | null;
  detail: string | null;
  createdAt: string | null;
}

export interface AuditPage {
  content: AuditLogDto[];
  totalElements: number;
  number: number;
  size: number;
  totalPages: number;
}

export interface AuditStats {
  total: number;
  allows: number;
  denies: number;
}

// ---------------- 交易风控（risk-service） ----------------

export interface TradeRequest {
  amount: number;
  channel: string;
  region: string;
  instrument: string;
}

export interface TradeExecution {
  id: number;
  username: string;
  amount: number;
  channel: string;
  region: string;
  instrument: string;
  effect: string;
  policyName: string;
  createdAt: number;
}

export interface TradeResult {
  effect: "PERMIT" | "DENY" | "REVIEW";
  permitted: boolean;
  policyName?: string | null;
  reason?: string | null;
  reviewId?: number | null;
  message?: string | null;
  execution?: TradeExecution | null;
}

export interface ReviewTask {
  id: number;
  username: string;
  amount: number;
  channel: string;
  region: string;
  instrument: string;
  policyName: string;
  createdAt: number;
  status: "PENDING" | "APPROVED" | "REJECTED";
  decidedBy?: string | null;
  decidedAt?: number | null;
}

export interface TradeStats {
  date: string;
  username: string;
  count: number;
  amountTotal: number;
  limitAmount: number;
  limitCount: number | null;
}

// ---------------- AI Agent 前置校验（agent-service） ----------------

export interface ToolCallRequest {
  agentId: string;
  trust: "high" | "medium" | "low";
  toolType: string;
  operation: string;
  params: Record<string, unknown>;
}

export interface ToolExecution {
  id: number;
  username: string;
  agentId: string;
  toolType: string;
  operation: string;
  summary: string;
  effect: string;
  policyName: string;
  createdAt: number;
}

export interface ToolResult {
  effect: "PERMIT" | "DENY" | "REVIEW";
  permitted: boolean;
  policyName?: string | null;
  reason?: string | null;
  reviewId?: number | null;
  message?: string | null;
  execution?: ToolExecution | null;
}

export interface AgentReviewTask {
  id: number;
  username: string;
  agentId: string;
  toolType: string;
  operation: string;
  summary: string;
  policyName: string;
  createdAt: number;
  status: "PENDING" | "APPROVED" | "REJECTED";
  decidedBy?: string | null;
  decidedAt?: number | null;
}

export interface SessionStats {
  sessionId: string;
  fetchCount: number;
  sendCount: number;
  transferCount: number;
  executeCount: number;
  deleteCount: number;
  limitFetch: number;
  limitSend: number;
}
