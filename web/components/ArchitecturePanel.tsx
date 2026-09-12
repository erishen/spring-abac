"use client";

/**
 * 架构图页：把 ABAC 的处理流程可视化，帮助使用者一屏看懂
 * 「请求怎么进来 → 谁在什么点裁决 → 三种结果分别去哪」。
 */
export default function ArchitecturePanel() {
  return (
    <div className="stack">
      {/* ============ 1. 系统总览 ============ */}
      <div className="card">
        <h2>系统总览：一条请求穿过哪些服务</h2>
        <p className="sub">
          前端只跟网关说话；网关（PEP）负责鉴权与属性注入，业务服务在关键操作前再问 PDP。
          决策与执行分离——策略全在 abac-service，业务服务不写权限逻辑。
        </p>

        <div className="arch-topo">
          {/* 客户端 */}
          <div className="arch-row">
            <div className="arch-node arch-client">
              <b>浏览器</b>
              <span>Web 控制台 :3001</span>
            </div>
            <div className="arch-flow-v">
              <span>JWT + 请求</span>
              <span className="arch-arrow">↓</span>
            </div>
          </div>

          {/* 网关 PEP */}
          <div className="arch-row">
            <div className="arch-node arch-pep arch-wide">
              <b>网关 gateway :4110（PEP）</b>
              <span>
                AuthGlobalFilter：验 JWT → 解析主体属性（clearance/department/region/title）
                注入 header → 按 URL 映射 (资源域, 动作) → 转发
              </span>
            </div>
            <div className="arch-flow-v">
              <span>带主体属性的转发</span>
              <span className="arch-arrow">↓</span>
            </div>
          </div>

          {/* 业务服务层 */}
          <div className="arch-row">
            <div className="arch-nodes">
              <div className="arch-node arch-svc">
                <b>document :4113</b>
                <span>行级属性注入 + 批量裁决</span>
              </div>
              <div className="arch-node arch-svc">
                <b>risk :4115</b>
                <span>当日累计注入</span>
              </div>
              <div className="arch-node arch-svc">
                <b>agent :4116</b>
                <span>会话计数 + 工具参数注入</span>
              </div>
            </div>
            <div className="arch-flow-v">
              <span>AbacClient 问 PDP</span>
              <span className="arch-arrow">↓</span>
            </div>
          </div>

          {/* PDP */}
          <div className="arch-row">
            <div className="arch-node arch-pdp arch-wide">
              <b>abac-service :4112（PDP 策略决策点）</b>
              <span>
                /api/decide：策略引擎按优先级求值（DENY 短路 deny-override → REVIEW →
                PERMIT → 默认拒绝），命中即返回 effect + 策略名 + 原因
              </span>
            </div>
          </div>

          {/* 支撑层 */}
          <div className="arch-row">
            <div className="arch-nodes">
              <div className="arch-node arch-misc">
                <b>eureka :8762</b>
                <span>服务注册发现</span>
              </div>
              <div className="arch-node arch-misc">
                <b>config :8889</b>
                <span>集中配置</span>
              </div>
              <div className="arch-node arch-misc">
                <b>audit :4114</b>
                <span>全链路审计（traceId）</span>
              </div>
              <div className="arch-node arch-misc">
                <b>auth :4111</b>
                <span>登录 / 用户属性（PIP 主体源）</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* ============ 2. 一次裁决链路 ============ */}
      <div className="card">
        <h2>一次裁决链路（以 GET /api/documents/12 为例）</h2>
        <p className="sub">
          六个步骤里只有第 4 步是 PDP 真正在做决策；其余全是「把属性凑齐」和「按结果执行」。
        </p>
        <div className="arch-flow">
          <div className="arch-step">
            <span className="arch-step-no">1</span>
            <div>
              <b>浏览器带 token 请求</b>
              <p>GET /api/documents/12，Header 带登录时拿到的 JWT。</p>
            </div>
          </div>
          <div className="arch-arrow">↓</div>
          <div className="arch-step">
            <span className="arch-step-no">2</span>
            <div>
              <b>网关 PEP：鉴权 + 注入主体属性</b>
              <p>
                校验 JWT → 解析出 subject ={" "}
                {`{ username: admin, department: EXEC, clearance: 5, region: CN, title: admin }`}
                ，写进转发 header；URL 映射为资源域 DOCUMENT、动作 READ。
              </p>
            </div>
          </div>
          <div className="arch-arrow">↓</div>
          <div className="arch-step">
            <span className="arch-step-no">3</span>
            <div>
              <b>document-service：行级属性 + 调 PDP</b>
              <p>
                读文档 12 的属性（owner / department / classification / requiredClearance），
                连同 subject 与动作打包，通过 AbacClient 问 abac-service 的 /api/decide。
              </p>
            </div>
          </div>
          <div className="arch-arrow">↓</div>
          <div className="arch-step arch-step-pdp">
            <span className="arch-step-no">4</span>
            <div>
              <b>PDP：策略引擎求值（这是唯一的决策点）</b>
              <p>
                按优先级从高到低逐条求值：P-100 非工作时段？P-95 境外？P-90 密级不足？……
                命中 DENY 立即短路返回 DENY；命中 PERMIT 返回 PERMIT；全部不中默认拒绝。
                这次请求命中 P-05 管理员全权 → PERMIT。
              </p>
            </div>
          </div>
          <div className="arch-arrow">↓</div>
          <div className="arch-step">
            <span className="arch-step-no">5</span>
            <div>
              <b>按 effect 执行或拒绝</b>
              <p>
                PERMIT → 返回文档 200；DENY → 403（带策略名 + 原因）。REVIEW → 业务服务挂起，
                进复核队列等 manager/admin 批准。
              </p>
            </div>
          </div>
          <div className="arch-arrow">↓</div>
          <div className="arch-step">
            <span className="arch-step-no">6</span>
            <div>
              <b>审计落库</b>
              <p>audit-service 记录整条 traceId 链路：谁、何时、对什么、什么结果。</p>
            </div>
          </div>
        </div>
      </div>

      {/* ============ 3. 三种结果去向 ============ */}
      <div className="card">
        <h2>三种裁决结果，三种去向</h2>
        <p className="sub">
          合并语义：DENY 优先级最高（短路），REVIEW 不会被 PERMIT 覆盖，全部不中默认拒绝（fail-closed）。
        </p>
        <div className="arch-verdict3">
          <div className="arch-verdict exp-permit">
            <b>PERMIT 放行</b>
            <span>操作正常执行，计入审计。</span>
          </div>
          <div className="arch-verdict exp-deny">
            <b>DENY 拒绝</b>
            <span>403 + 命中策略 + 原因；PDP 不可达时同样拒绝（fail-closed）。</span>
          </div>
          <div className="arch-verdict exp-review">
            <b>REVIEW 转人工复核</b>
            <span>不直接放行；manager/admin 批准后才执行并计入累计/会话状态。</span>
          </div>
        </div>
      </div>

      {/* ============ 4. 四组件在链路中的位置 ============ */}
      <div className="card">
        <h2>PAP / PDP / PEP / PIP：谁在哪干活</h2>
        <p className="sub">
          XACML 四组件在这个项目里的一一对应——知道「决策」在 PDP 做，就知道该去哪改策略、去哪看属性。
        </p>
        <div className="arch-components">
          <div className="arch-comp">
            <b className="arch-comp-name">PEP · 策略执行点</b>
            <span>网关 AuthGlobalFilter + 各业务服务的 AbacClient 调用点。只负责「问」和「按结果办」，不写判断逻辑。</span>
          </div>
          <div className="arch-comp">
            <b className="arch-comp-name">PDP · 策略决策点</b>
            <span>abac-service 的策略引擎：/api/decide 接收属性包，按优先级求值，返回 effect。决策唯一发生地。</span>
          </div>
          <div className="arch-comp">
            <b className="arch-comp-name">PAP · 策略管理点</b>
            <span>「管理 → 策略」页（admin 增删改，P-35）+ 启动种子 40 条。改这里立刻影响所有裁决（版本缓存失效）。</span>
          </div>
          <div className="arch-comp">
            <b className="arch-comp-name">PIP · 策略信息点</b>
            <span>主体属性（auth 用户表）、资源属性（文档 / 交易累计 / 工具参数）、环境属性（hour / region）。属性是裁决的全部输入。</span>
          </div>
        </div>
      </div>

      {/* ============ 5. 三个业务场景的链路差异 ============ */}
      <div className="card">
        <h2>三个业务场景，同一个 PDP</h2>
        <p className="sub">
          骨架一样（网关 PEP → 业务服务注入属性 → PDP），差别只在「谁注入什么属性」和「REVIEW 批准后怎么算」。
        </p>
        <div className="arch-compare">
          <div className="arch-compare-col">
            <h4>文档（document :4113）</h4>
            <p className="arch-compare-row"><b>注入</b>owner / department / classification / requiredClearance</p>
            <p className="arch-compare-row"><b>特点</b>列表逐行批量裁决；行级可见性由 P-90 / P-95 / P-20 把关</p>
            <p className="arch-compare-row"><b>REVIEW</b>当前无文档 REVIEW 策略（全 DENY/PERMIT）</p>
          </div>
          <div className="arch-compare-col">
            <h4>交易风控（risk :4115）</h4>
            <p className="arch-compare-row"><b>注入</b>amount / channel / region + 当日累计 cumulativeAfter</p>
            <p className="arch-compare-row"><b>特点</b>内存状态累计；P-80 累计超 10 万拒、P-85 大额转复核</p>
            <p className="arch-compare-row"><b>REVIEW</b>批准才计入当日累计（状态一致性）</p>
          </div>
          <div className="arch-compare-col">
            <h4>Agent 校验（agent :4116）</h4>
            <p className="arch-compare-row"><b>注入</b>agentId/trust + 工具参数 + 会话计数（sessionFetchCount 等）</p>
            <p className="arch-compare-row"><b>特点</b>16 条工具域策略；P-95 白名单 / P-80 危险命令 / P-75 转复核</p>
            <p className="arch-compare-row"><b>REVIEW</b>批准才执行并计入会话次数</p>
          </div>
        </div>
      </div>
    </div>
  );
}
