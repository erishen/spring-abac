# spring-abac 架构说明

> 配套文档：[README.md](README.md)（快速开始与功能）、[docs/adr/](docs/adr/)（决策记录）。
> 与 `spring-rbac` 是同一套骨架的对照实验，本文只讲 ABAC 侧多出来的部分。

## 1. 为什么要有 ABAC

RBAC 的表达力边界在"角色爆炸"：当"谁能看什么"取决于
部门、密级、地区、时间、作者这些**组合条件**时，角色数量按笛卡尔积增长——
`ENG_MANAGER_CN`、`ENG_MANAGER_US`、`SALES_VIEWER_CN`……
每加一个维度就翻倍，而每个角色内部仍是黑盒。

ABAC 把判定从"查表"换成"求值"：

```
决策 = f(主体属性, 资源属性, 环境属性, 动作)
```

加维度不再加角色，只加一条策略。代价是判定从 O(1) 查表变成 O(n) 求值，
需要缓存、需要短路、需要小心表达式安全——本文第 4、5 节讲的就是这些。

## 2. 术语与角色对应

沿用 NIST 的 ABAC 参考模型，本项目里每个角色都有具体落点：

| 角色 | 全称 | 本项目落点 | 说明 |
|---|---|---|---|
| PEP | Policy Enforcement Point | `gateway-service` 的 `AuthGlobalFilter` | 拦住请求、问 PDP、执行裁决 |
| PDP | Policy Decision Point | `abac-service` 的 `PolicyEngine` | 唯一做判断的地方 |
| PAP | Policy Administration Point | `abac-service` 的 `/api/policies` CRUD | 策略的增删改查 |
| PIP | Policy Information Point | `PipClient` + `document-service /internal/attributes/{id}` | 给 PDP 补资源属性 |
| 主体属性源 | Subject attribute source | `auth-service` 用户表 → JWT claims | 登录时快照进 token |
| 环境属性源 | Environment attribute source | 网关请求上下文（小时/星期/IP） | 每次请求实时生成 |

**关键约束：PDP 是唯一做判断的地方。** 业务服务不做 `if (user.isAdmin())`，
只把属性包递过去问"行不行"。策略改了，所有域立刻一起变，不存在漏改。

## 3. 属性从哪来

三类属性，三条不同的获取路径：

```
主体属性 ──登录时──> auth-service 读用户表 ──写进 JWT claims──> 每个请求带上
                                                              │
资源属性 ──两种来路──┬─ 调用方已带全（业务服务做行级过滤时）──┐
                     └─ 只有 id（网关 PEP 从 URL 解析）        │
                        └─> PDP 经 PIP 回源 document-service ─┘
                                                              │
环境属性 ──每次请求──> 网关生成（hour / dayOfWeek / ip）───────┘
                                                              │
                                                              ▼
                                                    PDP 拼成上下文求值
```

### 主体属性为什么进 JWT

登录时把 `department / clearance / region / title` 写进 claims，
每个请求都自带完整主体属性，PDP 不用回查 auth-service，省一次跨服务调用。

代价是**属性快照**：改了用户属性，旧 token 里的还是旧值，要等下次登录。
这是刻意的取舍——判定路径上少一次网络往返，比属性实时性更值钱。
真要实时，正确做法是 PDP 侧加短 TTL 缓存回查目录服务，而不是让每个请求都等一个 RPC。

### 资源属性的两种来路

- **调用方带全**：`document-service` 自己持有文档完整属性，做行级过滤时直接随请求带过去，
  省一次回源。批量接口 `/api/decide/batch` 就是为这个场景准备的。
- **只有 id**：网关 PEP 只认得 URL 里的 `/api/documents/12`，
  属性得由 PDP 经 PIP 回源 `document-service /internal/attributes/{id}` 补齐。

PIP 回源失败时按 `app.pip-fail-closed` 决定：
`true`（默认）直接拒绝——**拿不到资源属性就不知道该不该放行，那就别放行**；
`false` 按仅有的属性继续判定。安全默认必须是前者。

### 环境属性

`hour / dayOfWeek / ip`，网关每次请求实时生成，随裁决请求带给 PDP。
PDP 侧也有默认值兜底（直接调 `/api/decide` 不带 environment 时自动生成），
保证 PDP 可以脱离网关单独测试。

## 4. 策略模型与求值

### 策略长什么样

```java
@Entity
public class Policy {
    String  name;           // 唯一，人类可读
    Effect  effect;         // PERMIT / DENY / REVIEW（第三态：转人工复核）
    String  resourceType;   // DOCUMENT / USER / POLICY / AUDIT / * 或空
    String  action;         // READ / CREATE / UPDATE / DELETE / PUBLISH / * 或空
    String  condition;      // SpEL，可用 subject.* / resource.* / env.* / action
    int     priority;       // 数值越大越先判
    boolean enabled;
}
```

作用域（resourceType + action）是**粗筛**，条件表达式是**精筛**。
先按作用域过滤能砍掉大量无关策略，避免每条都去求值表达式。

### 求值算法

```
candidates = 所有 enabled 策略
           └─ 过滤 resourceType 匹配（* 或空 = 通配）
           └─ 过滤 action 匹配（* 或空 = 通配）
           └─ 按 priority 降序排序

for p in candidates:
    if p.condition 为空:
        matched = true                    # 无条件命中
    else:
        matched = 求值 SpEL(condition)    # 上下文 = subject/resource/env/action

    if matched and p.effect == DENY:
        return DENY                        # ← 短路，后面的都不看了
    if matched and p.effect == REVIEW:
        return REVIEW                      # ← 转人工复核，不被后面的 PERMIT 覆盖
    if matched and p.effect == PERMIT:
        return PERMIT

return DENY                                # 无命中 = 默认拒绝
```

### deny-override 是核心语义

DENY 一旦命中立即返回，后面的 PERMIT 根本不会被看到。这让"例外"可以被自然表达：

```
DOC-100  DENY   DOCUMENT/DELETE   env.hour < 9 || env.hour >= 18     ← 例外
DOC-05   PERMIT DOCUMENT/*        subject.title == 'admin'            ← 一般规则
```

管理员在深夜删文档照样被拒。RBAC 里 `ROLE_ADMIN` 是"有权限"的布尔值，
没法表达"有权限，但是……"——这正是 ABAC 表达力的关键点。

### REVIEW 第三态：从"放/拦"到"放/拦/复核"

两态裁决只回答"行不行"，REVIEW 增加"拿不准"：命中 REVIEW 的策略不直接拒绝、
也不直接放行，而是把这次访问落成**人工复核任务**，manager/admin 批准后才生效。

- 合并语义：**DENY 短路 REVIEW**（明确禁止 > 拿不准）；**REVIEW 不被 PERMIT 覆盖**
  （白名单兜底救不了拿不准的）；都不中才默认拒绝。
- 生效在业务侧：`risk-service`（TRD-85 单笔大额、TRD-70 工程师限额）与
  `agent-service`（EML-99 群发、COD-75 高危代码、PAY-90 大额转账）各自维护
  复核队列与状态累计——批准才计入当日累计/会话计数，PDP 不可达时 fail-closed。

**另外两条语义**：
- **默认拒绝**：无策略命中 = DENY。不给隐式放行，配置漏了是"不能用"而不是"全都能用"。
- **PERMIT 也要显式写**：没有"默认允许"这回事，白名单思维。

## 5. SpEL 沙箱

策略条件用 SpEL，是因为它零额外依赖（Spring 自带），语法对 Java 开发者零学习成本。
但 SpEL 能力太强——`T(java.lang.Runtime).getRuntime().exec(...)` 是合法表达式。
策略由管理员录入（可信输入），仍然做纵深防御：

**第一层：黑名单。** 用正则（不是子串！）匹配禁止片段：
`T(`、`new `、`getClass`、`\.class`、`ClassLoader`、`Runtime`、`ProcessBuilder`、`System.`、`@`、`exec(`。

> 踩过的坑：一开始用子串匹配 `.class`，把 `resource.classification` 误杀了——
> 条件里访问资源密级是最常见的写法。改正则后解决。

**第二层：类型定位器抛异常。** `StandardEvaluationContext.setTypeLocator()` 直接抛错，
让 `T(...)` 在解析阶段就失败，不依赖黑名单的完备性。

**第三层：Map 只读访问器。** 自定义 `PropertyAccessor` 只读 Map，
属性不存在返回 `null` 而不是抛异常——这样 `subject.foo == null` 这类条件能正常求值，
不会因为缺属性就整个判定失败。

**第四层：表达式缓存。** 解析过的表达式按字符串缓存（ConcurrentHashMap），
避免每次判定都重新解析 SpEL。策略更新时清缓存。

剩下能做的事：读属性、做比较、调字符串方法（`startsWith` / `contains`）、
算术与逻辑运算。摸不到 JVM。

## 6. 两道闸门：边缘 PEP + 服务内行级过滤

```
                        ┌──────────────── 网关 PEP（第 1 道）────────────────┐
GET /api/documents      │  JWT ✓  →  映射 (DOCUMENT, READ)  →  问 PDP  │
─────────────────────>  │          PDP 只有 id → PIP 回源补齐属性         │
                        └──────────────────────┬────────────────────────┘
                                               │ PERMIT，注入 X-Attr-*
                                               ▼
                        ┌─────────── document-service（第 2 道）───────────┐
                        │  逐行调 /api/decide/batch（自带完整属性）        │
                        │  只放行 permitted == true 的行 → 再分页          │
                        └─────────────────────────────────────────────────┘
```

**为什么要两道？** 两道闸门问的是同一个 PDP，所以不存在策略不一致。
- 第 1 道（网关）防的是"整个接口能不能碰"，成本低，一处配置全局生效；
- 第 2 道（业务服务）做的是**行级**，网关只认得 URL 里的 id，
  不知道列表里每一行是什么密级——只有持有数据的服务能做这个过滤。

只有第 1 道会出现"接口能进但内容全不该看"；只有第 2 道则每个服务都要自己接一遍 PEP。
两道的成本是可控的：第 2 道用批量接口，一次问完所有行。

**PDP 不可用时怎么办**：两道都是 fail-closed。
网关侧熔断打开 → 直接 403；业务服务侧调用异常 → 抛 `ForbiddenException`。
宁可拒绝也不能放行，这是授权系统的底线。

## 7. 熔断与降级

网关对 PDP 的调用挂了 Resilience4j 熔断器（`abac-decide` 实例）：
滑动窗口 10 次、失败率 50% 或慢调用率 50%（>800ms）即打开，
半开状态放 3 个探测请求。

**熔断触发 = 拒绝，不是放行。** 授权系统里"PDP 挂了就放行"是灾难性的设计——
一次 PDP 抖动就等于全站裸奔。熔断的真正作用是**快速失败**：
不让每个请求都卡等 PDP 超时把网关线程池拖垮，而是立刻返回 403。

审计发射是 fail-open：写审计失败只记日志，不影响业务。
审计是观测设施，不该成为可用性依赖。

## 8. 属性注入：网关 → 业务服务

网关校验完 JWT 后，把主体属性写成请求头注入下游：

```
X-User: alice
X-Attr-Department: ENG
X-Attr-Clearance: 3
X-Attr-Region: CN
X-Attr-Title: engineer
```

业务服务据此组装 subject 属性包，不用回查 auth-service。

**信任边界**：这些头的可信前提是"网关是唯一入口"。
内部服务只注册在 Eureka，不对外暴露端口，所以外部伪造不了这些头。
真要做到零信任，正确做法是业务服务也校验 JWT（或内网 mTLS），
本项目为了演示简洁选择了前者——这是刻意的取舍，不是疏忽。

## 9. 审计

每次裁决发射一条事件，字段比 RBAC 版多了三个：

```
traceId / actor / action / method / path / resourceId
decision (ALLOW|DENY) / status
policyId / policyName / reason   ← 多出来的
```

多记"命中的策略"，是为了回答一个 RBAC 答不了的问题：
**"这条访问是被哪条策略放/挡的？"** 策略多了以后，
"为什么我被拒绝了"会变成最高频的运维问题，审计里带上策略名是最省事的答案。

append-only：只有写入端点（带网关私有头 `X-Internal-Audit`，外部伪造不了），
没有更新和删除。查询端 `GET /api/audit` 本身也走 PEP（`AUDIT:READ` 只放 admin）。

## 10. 与 spring-rbac 的结构对照

| | spring-rbac | spring-abac |
|---|---|---|
| 判定服务 | rbac-service（角色树 + 权限表） | abac-service（策略表 + SpEL 引擎） |
| 判定输入 | `(user, permissionCode)` | `(subject, resource, action, environment)` |
| 判定输出 | boolean | `Decision(effect, policyId, policyName, reason, trace)` |
| 网关 PEP | 解析 JWT → 取权限码 → 问 rbac | 解析 JWT → 取属性 → 映射三元组 → 问 PDP |
| 业务域 | customer-service（无行级过滤） | document-service（行级过滤 + PIP 端点） |
| 前端 | 角色/权限/用户/客户/审批/审计 | 策略/模拟器/文档/用户属性/审计 |

骨架完全一致：Eureka + Config + Gateway + 业务域 + 审计 + Next.js + Makefile/compose/k3s。
差异集中在"判定"这一层，也正是 ABAC 的价值所在。

## 11. 生产化清单

演示项目的简化之处，真要上生产需要补齐：

| 项 | 现状 | 生产做法 |
|---|---|---|
| JWT 密钥 | 写死在 config-repo | 密钥管理服务，定期轮换 |
| 主体属性 | 快照进 JWT | PDP 侧短 TTL 缓存回查目录服务（LDAP / HR 系统） |
| 策略存储 | H2 单表 | 独立存储 + 版本 + 审批流 + 变更审计 |
| 决策性能 | 每条策略逐个求值 | 决策缓存（按属性组合哈希）+ 策略索引 |
| 行级过滤 | 全量取出后过滤 | 把属性条件下推到 SQL（数据量大时必需） |
| 表达式安全 | 黑名单 + 类型定位器 | 白名单函数 + 表达式静态分析 + 上线前 dry-run |
| PIP 可用性 | 同步调用 + fail-closed | 短路缓存 + 批量预取 + 降级属性策略 |
| 服务间信任 | 网关注入头 | 内网 mTLS 或服务侧校验 JWT |
