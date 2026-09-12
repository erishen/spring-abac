# spring-abac

基于属性的访问控制（ABAC）微服务演示系统：Spring Boot 3.2 + Spring Cloud 2023 + Next.js。

与同目录的 `spring-rbac`（基于角色的访问控制）是**对照实验**：同一套微服务骨架，
把"用户 → 角色 → 权限"这条链路换成"主体属性 + 资源属性 + 环境属性 → 策略表达式 → 裁决"，
看属性化授权能表达 RBAC 表达不了的东西。

```
RBAC：  用户 ──属于──> 角色 ──拥有──> 权限 ──> 能不能访问
ABAC：  (主体属性, 资源属性, 环境属性) ──策略表达式──> PERMIT / DENY
```

## 快速开始

```bash
make build     # 编译打包九个 jar（首次约 2-3 分钟）
make start     # 后台启动九服务 + 前端，等待就绪
make status    # 检查各服务可达性
make demo      # 端到端演示（纯 curl，看裁决如何随属性变化）
```

浏览器打开 <http://localhost:3001>。

演示账号（属性差异明显，同一条策略给出不同裁决）：

| 账号 | 密码 | department | clearance | region | title | 典型表现 |
|---|---|---|---|---|---|---|
| admin | admin123 | EXEC | 5 | CN | admin | 几乎全通，但仍被"非工作时间禁止删除"挡 |
| carol | carol123 | ENG | 4 | US | manager | 密级够，但境外地区读不到 CONFIDENTIAL |
| alice | alice123 | ENG | 3 | CN | engineer | 普通工程师，只看得到本部门内部文档 |
| bob | bob123 | SALES | 2 | CN | sales | 跨部门 + 低密级，可见面最窄 |

停止：`make stop`；清空数据库重来：`make reset-db` 后 `make start`。

## 架构

九个 Spring Boot 服务 + 一个 Next.js 前端。端口块整体与 spring-rbac 错开，可同时运行。

| 服务 | 端口 | 角色 | 职责 |
|---|---|---|---|
| eureka-server | 8762 | 注册中心 | 服务注册与发现 |
| config-server | 8889 | 配置中心 | native 后端，托管各服务 yml |
| gateway-service | 4110 | **PEP** | JWT 校验、动作/资源映射、问 PDP、注入属性头、发射审计 |
| auth-service | 4111 | 身份 + 属性源 | 注册/登录，签发**带主体属性的 JWT**（PIP 的主体侧） |
| abac-service | 4112 | **PDP** | 策略 CRUD、SpEL 条件求值、deny-override 合并、PIP 回源 |
| document-service | 4113 | 业务域 | 文档 CRUD、**行级 ABAC 过滤**、PIP 资源属性端点 |
| audit-service | 4114 | 审计 | append-only 记录每次裁决与命中策略 |
| risk-service | 4115 | 交易风控 | 下单预裁：单笔大额转**REVIEW 人工复核**、当日累计超限拒绝（内存状态） |
| agent-service | 4116 | Agent 前置校验 | 工具级策略：外链白名单/危险命令/群发复核/会话计数（内存状态） |
| web | 3001 | 前端 | Next.js BFF，`/api/*` rewrite 到网关 |

```
浏览器 ──> web:3001 ──rewrite──> gateway:4110 (PEP)
                                     │
            ┌──────────────┬─────────┼──────────┬───────────────┐
            ▼              ▼         ▼          ▼               ▼
     document:4113   risk:4115  abac:4112  agent:4116     audit:4114
     行级 ABAC       下单预裁    (PDP)     工具预裁        裁决事件
            │              ▲         │          ▲               ▲
            └──── PIP 回源 ─┴─────────┘──────────┘               │
                /internal/attributes/{id}   └──── 裁决事件 ──────┘
```

一次请求的完整链路（详见 [ARCHITECTURE.md](ARCHITECTURE.md)）：

1. 网关校验 JWT，把 `department / clearance / region / title` 从 claims 取出作为**主体属性**；
2. 按路径 + 方法映射成三元组（如 `GET /api/documents/12` → `DOCUMENT / READ / 12`）；
3. 带上环境属性（小时、星期、来源 IP）问 PDP：`POST abac-service /api/decide`；
4. PDP 筛出作用域匹配的策略，按优先级降序求值 SpEL 条件，**遇到 DENY 立即返回**；
   效果三态：**PERMIT** 放行、**DENY** 拒绝、**REVIEW** 转人工复核（如 TRD-85 单笔大额、EML-99 群发）——REVIEW 由业务服务落队列，manager/admin 批准后才生效；
5. PDP 只拿到资源 id 时，经 **PIP** 回源 `document-service /internal/attributes/{id}` 补齐资源属性；
6. PERMIT 才放行，并把 `X-User / X-Attr-*` 注入下游；
7. 业务服务再按完整资源属性问一次 PDP，做**行级过滤**（列表只返回读得到的行）；
8. 网关异步发射审计事件（含命中的策略），fail-open 不影响业务。

## 策略长什么样

策略存在 H2 里，四个要素：**作用域**（资源类型 + 动作）、**SpEL 条件**、**效果**、**优先级**。

```
DOC-100  DENY   DOCUMENT / DELETE    env.hour < 9 || env.hour >= 18        优先级 100
DOC-95   DENY   DOCUMENT / READ      resource.classification == 'CONFIDENTIAL'
                                      && subject.region != 'CN'            优先级 95
DOC-90   DENY   DOCUMENT / READ      subject.clearance < resource.requiredClearance   90
DOC-30   PERMIT DOCUMENT / READ      resource.classification == 'PUBLIC'    30
DOC-20   PERMIT DOCUMENT / READ      resource.department == subject.department        20
DOC-18   PERMIT DOCUMENT / LIST      （无条件，列表放行、行级过滤兜底）      18
DOC-10   PERMIT DOCUMENT / *         resource.owner == subject.username     10
DOC-05   PERMIT DOCUMENT / *         subject.title == 'admin'               5
```

**PERMIT 只回答"够不够格"，不回答"安不安全"**——密级（DOC-90）、数据属地（DOC-95）
这类硬性约束一律写成 DENY，所以 DOC-20 放开"同部门"并不会让密级不够的人多看到东西。

`LIST` 是集合动作：网关对 `GET /api/documents` 这类没有资源 id 的请求，
问的是"能不能列这个域"，而不是"能不能看某一行"——列不出来具体资源属性，
按 READ 问会全部落空撞上默认拒绝。真正的行级边界在业务服务。

三条关键语义：

- **deny-override**：DENY 优先级最高，一旦命中立即短路。所以"管理员全权"（DOC-05）翻不了
  "非工作时间禁止删除"（DOC-100）的案——这正是 RBAC 里 `ROLE_ADMIN` 一把梭做不到的。
- **默认拒绝**：没有任何策略命中 = DENY，不给隐式放行。
- **属性来自三方**：`subject.*`（JWT）、`resource.*`（业务库，经 PIP 回源）、`env.*`（请求上下文）。

SpEL 是沙箱化的：禁类型引用（`T(...)`）、禁构造、禁 `.class`/反射出口，
表达式只能读属性、做比较，摸不到 JVM。

## 与 spring-rbac 的差异

| 维度 | spring-rbac | spring-abac |
|---|---|---|
| 授权依据 | 用户 → 角色 → 权限（静态绑定） | 主体/资源/环境属性 → 策略表达式（动态求值） |
| 判定入口 | `POST /api/check?user&permission` | `POST /api/decide`（带完整属性包） |
| 谁能看什么 | 权限码写死在代码里 | 由属性决定，改属性即改权限，不动代码 |
| 管理员 | `ROLE_ADMIN` 通吃 | `title == 'admin'` 的 PERMIT 仍受 DENY 约束 |
| 行级过滤 | 无（列表全量返回） | 有：逐行问 PDP，只放行读得到的行 |
| 冲突处理 | 权限叠加，无法表达"例外" | deny-override：高优先级 DENY 天然表达例外 |
| 策略存储 | 角色树 + 权限表 | Policy 表（含 SpEL 表达式） |
| 端口块 | 8761 / 8888 / 41xx / web 3000 | 8762 / 8889 / 411x / web 3001 |

## 前端九个面板（四组 Tab）

- **演示**：裁决模拟——手工拼属性包直接问 PDP，13 个预设场景点选即演示，看裁决随属性翻转。
- **业务**：交易风控（下单预裁 + 当日累计 + 人工复核队列）、Agent 校验（五类工具预裁 + 会话统计 + 复核队列）、文档（行级 ABAC 过滤，换账号看到的行数不同）。
- **管理**：策略（PAP，增删改停只放开 admin）、用户属性（改 clearance/region/title 立刻改变可见范围）、审计（裁决流水 + 命中策略）。
- **参考**：字典（ABAC/四组件/策略结构速查）、架构（一次请求穿过哪些服务 + 裁决链路图）。

## 运行方式三选一

```bash
# 1) 裸 jar（本地调试，前端 next dev 热更新）
make start

# 2) Docker Compose
make docker-up     # make docker-ps 看状态，make docker-demo 跑演示

# 3) k3s（OrbStack / 单节点）
make k3s-build && make k3s-deploy
make k3s-demo      # 端口转发网关到 41100 后跑演示
```

三者端口互不冲突，且与 spring-rbac 的 compose / k3s 部署可并存。

## 测试

```bash
make test          # 或 mvn test（全模块，63 个用例）
```

| 模块 | 用例 | 覆盖点 |
|---|---|---|
| abac-service | 15 | 策略引擎：deny-override 合并、优先级排序、作用域过滤、SpEL 沙箱黑名单、表达式缓存、REVIEW 三态合并、PIP fail-closed |
| document-service | 23 | 行级过滤（只放行 PDP 同意的行）、缺裁决按不可见、分页边界、对象级校验、PDP 不可用 fail-closed、PDP 客户端解析与批量映射 |
| gateway-service | 7 | PEP 认证边界：公开路由放行、/api 无 token / 伪 token 一律 401 |
| auth-service | 11 | 全局异常处理、注册不接收属性自报（默认低权限）、属性修改仅 admin（USR-60 业务层第二道） |
| risk-service | 3 | 交易风控：单笔大额 REVIEW、当日累计、fail-closed |
| agent-service | 4 | Agent 预裁：外链白名单、危险命令、会话计数、fail-closed |

关键安全链路（PEP 认证 → PDP 裁决 → 行级过滤 → fail-closed）都有回归测试保护。

## 目录结构

```
spring-abac/
├── pom.xml                 # 父 POM（9 模块，Spring Boot 3.2.5 / Cloud 2023.0.3）
├── eureka-server/          # 8762
├── config-server/          # 8889 + config-repo/*.yml
├── gateway-service/        # 4110  PEP
├── auth-service/           # 4111  身份 + 主体属性
├── abac-service/           # 4112  PDP + SpEL 引擎 + PIP
├── document-service/       # 4113  业务域 + 行级过滤
├── audit-service/          # 4114  审计
├── risk-service/           # 4115  交易风控（下单预裁 + REVIEW 人工复核）
├── agent-service/          # 4116  Agent 前置校验（工具预裁 + REVIEW 人工复核）
├── web/                    # 3001  Next.js
├── scripts/demo.sh         # 端到端 curl 演示
├── docker-compose.yml
├── k8s/spring-abac.yaml
├── docs/adr/               # 架构决策记录
├── ARCHITECTURE.md
├── README.md
└── README.zh.md
```

## 隐私与合规基线（演示口径）

- **注册不收集属性**：主体属性是授权依据，**不允许自报**（否则任何人注册
  `clearance=5, title=admin` 直接成为管理员）。新账号默认低权限
  （ENG / clearance 1 / CN / engineer），提权须 admin 操作。
- **属性修改仅 admin**：网关策略 `USR-60`（USER/UPDATE 仅 admin）是第一道，
  auth-service 业务层再校验调用者 `title == 'admin'` 是第二道——内网直连也改不了。
- **JWT 属性是快照**：改属性后，目标用户旧 token 仍带旧属性直到过期（默认 24h），
  **重新登录才生效**；生产需引入属性版本号 / token 黑名单。
- **审计写入防伪**：网关 → 审计的 `X-Internal-Audit` 头不再写死，两侧都读
  `APP_INTERNAL_SECRET`（默认 dev-only-internal-secret-change-me）。
- **演示数据**：仅收集用户名 + PBKDF2 密码哈希 + 部门/密级/属地/岗位属性，
  用于演示授权语义；生产部署需自行补齐隐私政策、告知同意、账号删除等
  个保法（PIPL）义务。

## 说明

这是演示项目：JWT 密钥默认 `dev-only-secret-change-me-please`（仅演示），
**生产部署设环境变量 `APP_JWT_SECRET` 即可覆盖，无需改代码**；
数据库用 H2 文件库且 `ddl-auto: create`（每次启动重建）。
生产化需要换掉这些：**密钥进密钥管理服务、策略进独立存储并加版本与审批、
PDP 加决策缓存与批量接口、属性源接真实目录服务与业务库**。
