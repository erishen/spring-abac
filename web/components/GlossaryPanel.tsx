"use client";

/** ABAC 术语字典：把 PAP/PDP/PEP/PIP 与项目里每个角落一一对应，作为速查页。 */
export default function GlossaryPanel() {
  return (
    <div>
      <div className="card">
        <h2>ABAC 是什么</h2>
        <p className="sub">
          ABAC（Attribute-Based Access Control，基于属性的访问控制）：不预设
          "谁有什么角色"，而是把每次请求都拆成一组<b>属性</b>（你是谁、访问什么、
          什么环境），交给策略表达式现场求值，得出放行或拒绝。属性变 → 权限变，
          改权限不用改代码。
        </p>
        <table className="cust-table">
          <thead>
            <tr>
              <th>维度</th>
              <th>RBAC（角色）</th>
              <th>ABAC（属性）</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td className="name">授权依据</td>
              <td>用户 → 角色 → 权限（静态绑定）</td>
              <td>主体/资源/环境属性 → 策略表达式（动态求值）</td>
            </tr>
            <tr>
              <td className="name">谁能看什么</td>
              <td>权限码写死在代码里</td>
              <td>由属性决定，改属性即改权限</td>
            </tr>
            <tr>
              <td className="name">管理员</td>
              <td>ROLE_ADMIN 通吃</td>
              <td>title==&apos;admin&apos; 的 PERMIT 仍受 DENY 约束</td>
            </tr>
            <tr>
              <td className="name">例外表达</td>
              <td>权限叠加，无法表达"例外"</td>
              <td>deny-override：高优先级 DENY 天然表达例外</td>
            </tr>
            <tr>
              <td className="name">行级过滤</td>
              <td>无（列表全量返回）</td>
              <td>有：逐行问 PDP，只放行读得到的行</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div className="card">
        <h2>四大组件（XACML 模型）</h2>
        <p className="sub">
          "管 → 判 → 执 → 取数"四个点分工协作；本项目的落点都标注了对应服务和页面。
        </p>
        <div
          style={{
            display: "flex",
            flexWrap: "wrap",
            gap: 12,
            marginTop: 4,
          }}
        >
          <div
            style={{
              flex: "1 1 220px",
              minWidth: 220,
              background: "#fff",
              border: "1px solid var(--line)",
              borderLeft: "4px solid #3b82f6",
              borderRadius: 10,
              padding: "12px 14px",
            }}
          >
            <div style={{ fontWeight: 700 }}>PAP · 策略管理点</div>
            <div style={{ fontSize: 12, color: "var(--muted)" }}>
              Policy Administration Point
            </div>
            <div style={{ fontSize: 13, marginTop: 6, lineHeight: 1.6 }}>
              策略的定义、存储、维护、启停。只管"规则本身"，不参与每次判定。
            </div>
            <div
              style={{
                fontSize: 12.5,
                color: "var(--muted)",
                marginTop: 8,
                background: "var(--bg)",
                borderRadius: 6,
                padding: "6px 8px",
              }}
            >
              本项目：abac-service 的 /api/policies + "策略"页（增删改停，仅 admin）
            </div>
          </div>

          <div
            style={{
              flex: "1 1 220px",
              minWidth: 220,
              background: "#fff",
              border: "1px solid var(--line)",
              borderLeft: "4px solid #8b5cf6",
              borderRadius: 10,
              padding: "12px 14px",
            }}
          >
            <div style={{ fontWeight: 700 }}>PDP · 策略决策点</div>
            <div style={{ fontSize: 12, color: "var(--muted)" }}>
              Policy Decision Point
            </div>
            <div style={{ fontSize: 13, marginTop: 6, lineHeight: 1.6 }}>
              吃进属性包 + 策略集，按优先级求值 SpEL 条件，输出 ALLOW / DENY。
              每次请求现场判定，无状态、可水平扩展。
            </div>
            <div
              style={{
                fontSize: 12.5,
                color: "var(--muted)",
                marginTop: 8,
                background: "var(--bg)",
                borderRadius: 6,
                padding: "6px 8px",
              }}
            >
              本项目：abac-service 的 PolicyEngine（"裁决模拟"页可直接调）
            </div>
          </div>

          <div
            style={{
              flex: "1 1 220px",
              minWidth: 220,
              background: "#fff",
              border: "1px solid var(--line)",
              borderLeft: "4px solid #10b981",
              borderRadius: 10,
              padding: "12px 14px",
            }}
          >
            <div style={{ fontWeight: 700 }}>PEP · 策略执行点</div>
            <div style={{ fontSize: 12, color: "var(--muted)" }}>
              Policy Enforcement Point
            </div>
            <div style={{ fontSize: 13, marginTop: 6, lineHeight: 1.6 }}>
              拦截请求、校验凭证、取出主体属性，把 PDP 的裁决落地：放行、注入
              下游头、拒绝。是请求的第一道门。
            </div>
            <div
              style={{
                fontSize: 12.5,
                color: "var(--muted)",
                marginTop: 8,
                background: "var(--bg)",
                borderRadius: 6,
                padding: "6px 8px",
              }}
            >
              本项目：网关 AuthGlobalFilter（验 JWT + 预裁决）+ 各服务接口（行级裁决）
            </div>
          </div>

          <div
            style={{
              flex: "1 1 220px",
              minWidth: 220,
              background: "#fff",
              border: "1px solid var(--line)",
              borderLeft: "4px solid #f59e0b",
              borderRadius: 10,
              padding: "12px 14px",
            }}
          >
            <div style={{ fontWeight: 700 }}>PIP · 策略信息点</div>
            <div style={{ fontSize: 12, color: "var(--muted)" }}>
              Policy Information Point
            </div>
            <div style={{ fontSize: 13, marginTop: 6, lineHeight: 1.6 }}>
              裁决前补齐属性：主体属性（JWT）、资源属性（业务库）、环境属性
              （请求上下文）。PDP 拿不到属性就找它回源。
            </div>
            <div
              style={{
                fontSize: 12.5,
                color: "var(--muted)",
                marginTop: 8,
                background: "var(--bg)",
                borderRadius: 6,
                padding: "6px 8px",
              }}
            >
              本项目：auth 签发的 JWT claims、document-service /internal/attributes、
              abac-service 的 PipClient
            </div>
          </div>
        </div>
      </div>

      <div className="card">
        <h2>一次裁决链路（以 GET /api/documents/12 为例）</h2>
        <table className="cust-table">
          <thead>
            <tr>
              <th>#</th>
              <th>环节</th>
              <th>谁在做</th>
              <th>发生了什么</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>1</td>
              <td className="name">凭证校验</td>
              <td>PEP · 网关</td>
              <td>验 JWT，从 claims 取出主体属性：department / clearance / region / title</td>
            </tr>
            <tr>
              <td>2</td>
              <td className="name">路径映射</td>
              <td>PEP · 网关</td>
              <td>GET /api/documents/12 → 三元组 DOCUMENT / READ / 12</td>
            </tr>
            <tr>
              <td>3</td>
              <td className="name">环境上下文</td>
              <td>PEP · 网关</td>
              <td>带上 env：小时、星期、来源 IP，POST abac-service /api/decide</td>
            </tr>
            <tr>
              <td>4</td>
              <td className="name">属性补齐</td>
              <td>PIP</td>
              <td>PDP 只有资源 id，经 PIP 回源 document-service /internal/attributes/12 取密级等</td>
            </tr>
            <tr>
              <td>5</td>
              <td className="name">策略求值</td>
              <td>PDP · PolicyEngine</td>
              <td>筛作用域匹配的策略，按优先级降序求 SpEL；命中 DENY 立即短路</td>
            </tr>
            <tr>
              <td>6</td>
              <td className="name">放行 / 拒绝</td>
              <td>PEP · 网关</td>
              <td>PERMIT 才放行并注入 X-User / X-Attr-*；默认拒绝，无命中即 DENY</td>
            </tr>
            <tr>
              <td>7</td>
              <td className="name">行级过滤</td>
              <td>业务服务</td>
              <td>列表场景逐行再问一次 PDP，只返回读得到的行（"文档"页即此效果）</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div className="card">
        <h2>策略长什么样</h2>
        <p className="sub">
          四个要素：作用域（资源类型 + 动作）、SpEL 条件、效果、优先级。
          PERMIT 只回答"够不够格"，密级/属地等硬约束一律写 DENY（deny-override）。
        </p>
        <table className="cust-table">
          <thead>
            <tr>
              <th>策略</th>
              <th>效果</th>
              <th>作用域</th>
              <th>条件（SpEL）</th>
              <th>优先级</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td className="name">P-100</td>
              <td>DENY</td>
              <td>DOCUMENT / DELETE</td>
              <td>env.hour &lt; 9 || env.hour &gt;= 18（非工作时间禁删）</td>
              <td>100</td>
            </tr>
            <tr>
              <td className="name">P-95</td>
              <td>DENY</td>
              <td>DOCUMENT / READ</td>
              <td>classification==CONFIDENTIAL &amp;&amp; region != CN（境外禁读机密）</td>
              <td>95</td>
            </tr>
            <tr>
              <td className="name">P-90</td>
              <td>DENY</td>
              <td>DOCUMENT / READ</td>
              <td>subject.clearance &lt; resource.requiredClearance（密级不够）</td>
              <td>90</td>
            </tr>
            <tr>
              <td className="name">P-30</td>
              <td>PERMIT</td>
              <td>DOCUMENT / READ</td>
              <td>resource.classification == PUBLIC（公开文档人人可读）</td>
              <td>30</td>
            </tr>
            <tr>
              <td className="name">P-20</td>
              <td>PERMIT</td>
              <td>DOCUMENT / READ</td>
              <td>resource.department == subject.department（同部门）</td>
              <td>20</td>
            </tr>
            <tr>
              <td className="name">P-10</td>
              <td>PERMIT</td>
              <td>DOCUMENT / *</td>
              <td>resource.owner == subject.username（本人文档）</td>
              <td>10</td>
            </tr>
            <tr>
              <td className="name">P-05</td>
              <td>PERMIT</td>
              <td>DOCUMENT / *</td>
              <td>subject.title == admin（管理员放行，仍受 DENY 约束）</td>
              <td>5</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div className="card">
        <h2>属性三方</h2>
        <p className="sub">
          每条策略里的变量都来自这三方之一；这也是"改属性即改权限"的落点。
        </p>
        <table className="cust-table">
          <thead>
            <tr>
              <th>命名空间</th>
              <th>含义</th>
              <th>本项目来源</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td className="name">subject.*</td>
              <td>发起请求的主体属性</td>
              <td>JWT claims（username / department / clearance / region / title）</td>
            </tr>
            <tr>
              <td className="name">resource.*</td>
              <td>被访问资源的属性</td>
              <td>document-service 业务库（classification → requiredClearance / department / owner）</td>
            </tr>
            <tr>
              <td className="name">env.*</td>
              <td>请求环境属性</td>
              <td>网关注入（hour / weekday / clientIp）</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div className="card">
        <h2>密级与 clearance</h2>
        <p className="sub">
          文档密级映射到所需 clearance，策略 P-90 据此挡人；改用户 clearance
          立刻改变可见范围（"用户属性"页可试）。
        </p>
        <table className="cust-table">
          <thead>
            <tr>
              <th>clearance</th>
              <th>密级</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td className="name">5</td>
              <td>绝密 SECRET</td>
              <td>如 admin（EXEC）</td>
            </tr>
            <tr>
              <td className="name">4</td>
              <td>机密 CONFIDENTIAL</td>
              <td>如 carol（ENG / manager），密级够但境外读不到 CONFIDENTIAL（P-95）</td>
            </tr>
            <tr>
              <td className="name">3</td>
              <td>受限（中间档）</td>
              <td>如 alice（ENG / engineer），只看得到本部门内部文档</td>
            </tr>
            <tr>
              <td className="name">2</td>
              <td>内部 INTERNAL</td>
              <td>如 bob（SALES），跨部门 + 低密级，可见面最窄</td>
            </tr>
            <tr>
              <td className="name">1</td>
              <td>公开 PUBLIC</td>
              <td>最低档，仅公开文档</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div className="card">
        <h2>术语速查</h2>
        <table className="cust-table">
          <thead>
            <tr>
              <th>术语</th>
              <th>全称</th>
              <th>一句话</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td className="name">ABAC</td>
              <td>Attribute-Based Access Control</td>
              <td>基于属性的访问控制，本项目的授权模型</td>
            </tr>
            <tr>
              <td className="name">XACML</td>
              <td>eXtensible Access Control Markup Language</td>
              <td>定义 PEP/PDP/PIP/PAP 协作模型的标准</td>
            </tr>
            <tr>
              <td className="name">PAP</td>
              <td>Policy Administration Point</td>
              <td>策略管理点：规则的增删改停</td>
            </tr>
            <tr>
              <td className="name">PDP</td>
              <td>Policy Decision Point</td>
              <td>策略决策点：属性 + 策略 → ALLOW / DENY</td>
            </tr>
            <tr>
              <td className="name">PEP</td>
              <td>Policy Enforcement Point</td>
              <td>策略执行点：拦截请求、落地裁决</td>
            </tr>
            <tr>
              <td className="name">PIP</td>
              <td>Policy Information Point</td>
              <td>策略信息点：裁决前补齐属性</td>
            </tr>
            <tr>
              <td className="name">SpEL</td>
              <td>Spring Expression Language</td>
              <td>策略条件表达式（沙箱化，禁反射/类型引用）</td>
            </tr>
            <tr>
              <td className="name">JWT</td>
              <td>JSON Web Token</td>
              <td>登录凭证，也是主体属性的载体</td>
            </tr>
            <tr>
              <td className="name">deny-override</td>
              <td>—</td>
              <td>DENY 优先，命中即短路；高优先级 DENY 表达"例外"</td>
            </tr>
            <tr>
              <td className="name">默认拒绝</td>
              <td>—</td>
              <td>无策略命中 = DENY，不给隐式放行</td>
            </tr>
            <tr>
              <td className="name">行级过滤</td>
              <td>—</td>
              <td>列表逐行问 PDP，只返回读得到的行</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  );
}
