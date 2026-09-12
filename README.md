# spring-abac

Attribute-Based Access Control (ABAC) microservice demo: Spring Boot 3.2 + Spring Cloud 2023 + Next.js.

It sits next to `spring-rbac` (role-based access control) in the same directory as a **controlled experiment**:
the same microservice skeleton, but the "user → role → permission" chain is replaced by
"subject attributes + resource attributes + environment attributes → policy expressions → decision",
to show what attribute-based authorization can express that RBAC cannot.

```
RBAC:  user ──belongs to──> role ──has──> permission ──> can access?
ABAC:  (subject, resource, environment) ──policy expression──> PERMIT / DENY
```

## Quick start

```bash
make build     # build nine jars (first run ~2-3 min)
make start     # start nine services + frontend in background, wait until ready
make status    # check reachability of every service
make demo      # end-to-end demo (plain curl, watch decisions flip with attributes)
```

Open <http://localhost:3001> in a browser.

Demo accounts (attribute differences are large, so the same policy yields different decisions):

| account | password | department | clearance | region | title | typical behavior |
|---|---|---|---|---|---|---|
| admin | admin123 | EXEC | 5 | CN | admin | passes almost everything, still blocked by "no deletion after hours" |
| carol | carol123 | ENG | 4 | US | manager | clearance is fine, but cannot read CONFIDENTIAL from outside CN |
| alice | alice123 | ENG | 3 | CN | engineer | ordinary engineer; only sees internal docs of her own department |
| bob | bob123 | SALES | 2 | CN | sales | cross-department + low clearance, narrowest visibility |

Stop: `make stop`; wipe the database and start over: `make reset-db` then `make start`.

## Architecture

Nine Spring Boot services + one Next.js frontend. The port block is disjoint from spring-rbac, so both can run at the same time.

| service | port | role | responsibility |
|---|---|---|---|
| eureka-server | 8762 | registry | service registration and discovery |
| config-server | 8889 | config center | native backend, hosts each service's yml |
| gateway-service | 4110 | **PEP** | JWT validation, action/resource mapping, asks PDP, injects attribute headers, emits audit |
| auth-service | 4111 | identity + attribute source | register/login, issues a **JWT carrying subject attributes** (PIP's subject side) |
| abac-service | 4112 | **PDP** | policy CRUD, SpEL condition evaluation, deny-override merge, PIP backfill |
| document-service | 4113 | business domain | document CRUD, **row-level ABAC filtering**, PIP resource-attribute endpoint |
| audit-service | 4114 | audit | append-only records of every decision and the hit policy |
| risk-service | 4115 | trading risk control | order pre-check: single large transfer → **REVIEW (manual review)**, daily accumulation over limit → DENY (in-memory state) |
| agent-service | 4116 | agent pre-check | tool-level policies: external-link allowlist / dangerous commands / broadcast review / session counting (in-memory state) |
| web | 3001 | frontend | Next.js BFF, rewrites `/api/*` to the gateway |

```
browser ──> web:3001 ──rewrite──> gateway:4110 (PEP)
                                     │
            ┌──────────────┬─────────┼──────────┬───────────────┐
            ▼              ▼         ▼          ▼               ▼
     document:4113   risk:4115  abac:4112  agent:4116     audit:4114
     row-level ABAC  order pre-  (PDP)     tool pre-      decision
                      check                check          events
            │              ▲         │          ▲               ▲
            └──── PIP backfill ──────┴──────────┘               │
                /internal/attributes/{id}   └── decision events ┘
```

The full path of one request (details in [ARCHITECTURE.md](ARCHITECTURE.md)):

1. The gateway validates the JWT and takes `department / clearance / region / title` out of the claims as **subject attributes**;
2. Maps path + method to a triple (e.g. `GET /api/documents/12` → `DOCUMENT / READ / 12`);
3. Asks the PDP with environment attributes (hour, weekday, source IP): `POST abac-service /api/decide`;
4. The PDP filters policies by scope, evaluates SpEL conditions in descending priority order, and **returns immediately on the first DENY**.
   Effects are three-state: **PERMIT** allow, **DENY** deny, **REVIEW** escalate to manual review (e.g. TRD-85 single large transfer, EML-99 bulk send) — REVIEW lands in a queue owned by the business service and only takes effect after a manager/admin approves;
5. When the PDP only has a resource id, it backfills resource attributes via **PIP** from `document-service /internal/attributes/{id}`;
6. Only PERMIT passes, and `X-User / X-Attr-*` are injected downstream;
7. The business service asks the PDP again with the full resource attributes for **row-level filtering** (a list only returns rows the caller may read);
8. The gateway asynchronously emits an audit event (including the hit policy), fail-open so it never blocks business.

## What a policy looks like

Policies live in H2 and have four elements: **scope** (resource type + action), **SpEL condition**, **effect**, **priority**.

```
DOC-100  DENY   DOCUMENT / DELETE    env.hour < 9 || env.hour >= 18        priority 100
DOC-95   DENY   DOCUMENT / READ      resource.classification == 'CONFIDENTIAL'
                                      && subject.region != 'CN'            priority 95
DOC-90   DENY   DOCUMENT / READ      subject.clearance < resource.requiredClearance   90
DOC-30   PERMIT DOCUMENT / READ      resource.classification == 'PUBLIC'   30
DOC-20   PERMIT DOCUMENT / READ      resource.department == subject.department        20
DOC-18   PERMIT DOCUMENT / LIST      (unconditional; list passes, row-level filter is the backstop)  18
DOC-10   PERMIT DOCUMENT / *         resource.owner == subject.username     10
DOC-05   PERMIT DOCUMENT / *         subject.title == 'admin'               5
```

**PERMIT only answers "are you eligible", not "is it safe"** — hard constraints such as clearance (DOC-90)
and data residency (DOC-95) are always written as DENY, so DOC-20 opening up "same department"
does not let under-cleared users see more.

`LIST` is a collection action: for requests without a resource id (e.g. `GET /api/documents`), the gateway asks
"may I enumerate this domain" rather than "may I read that row" — without concrete resource attributes,
asking as READ would hit the default deny everywhere. The real row-level boundary lives in the business service.

Three key semantics:

- **deny-override**: DENY has the highest priority and short-circuits as soon as it hits. So "admin full access" (DOC-05)
  cannot overturn "no deletion after hours" (DOC-100) — exactly what RBAC's `ROLE_ADMIN` pass-everything cannot do.
- **Default deny**: no policy matched = DENY; no implicit allow.
- **Attributes come from three sources**: `subject.*` (JWT), `resource.*` (business DB, backfilled via PIP), `env.*` (request context).

SpEL is sandboxed: type references (`T(...)`), constructors, `.class`/reflection escapes are forbidden;
expressions may only read attributes and compare — they cannot reach the JVM.

## Differences from spring-rbac

| dimension | spring-rbac | spring-abac |
|---|---|---|
| authorization basis | user → role → permission (static binding) | subject/resource/environment attributes → policy expressions (dynamic evaluation) |
| decision entry | `POST /api/check?user&permission` | `POST /api/decide` (full attribute bundle) |
| who can see what | permission codes hard-coded | decided by attributes; change attributes, change access, no code change |
| admin | `ROLE_ADMIN` passes everything | `title == 'admin'` PERMIT is still constrained by DENY |
| row-level filtering | none (lists return everything) | yes: ask PDP per row, only return readable rows |
| conflict handling | permissions accumulate; no way to express exceptions | deny-override: high-priority DENY expresses exceptions naturally |
| policy storage | role tree + permission table | Policy table (with SpEL expressions) |
| port block | 8761 / 8888 / 41xx / web 3000 | 8762 / 8889 / 411x / web 3001 |

## Frontend: nine panels (four tab groups)

- **Demo**: decision simulator — hand-build an attribute bundle and ask the PDP directly; 13 preset scenarios, click to play, watch decisions flip with attributes.
- **Business**: trading risk control (order pre-check + daily accumulation + manual review queue), agent check (five tool pre-checks + session stats + review queue), documents (row-level ABAC filtering; each account sees a different number of rows).
- **Management**: policies (PAP; CRUD and enable/disable are admin-only), user attributes (changing clearance/region/title immediately changes visibility), audit (decision feed + hit policies).
- **Reference**: dictionary (ABAC / the four components / policy structure quick reference), architecture (which services one request crosses + decision-path diagram).

## Three ways to run

```bash
# 1) Bare jars (local debugging; frontend gets next dev hot reload)
make start

# 2) Docker Compose
make docker-up     # make docker-ps to check state, make docker-demo to run the demo

# 3) k3s (OrbStack / single node)
make k3s-build && make k3s-deploy
make k3s-demo      # port-forward the gateway to 41100, then run the demo
```

The three options use disjoint ports and can coexist with the spring-rbac compose / k3s deployments.

## Tests

```bash
make test          # or mvn test (all modules, 63 cases)
```

| module | cases | coverage |
|---|---|---|
| abac-service | 15 | policy engine: deny-override merge, priority ordering, scope filtering, SpEL sandbox blacklist, expression cache, REVIEW three-state merge, PIP fail-closed |
| document-service | 23 | row-level filtering (only rows the PDP permits), missing decisions treated as invisible, pagination bounds, object-level checks, fail-closed on PDP unavailability, client parsing and batch mapping |
| gateway-service | 7 | PEP auth boundary: public routes pass through; /api without or with a forged token → 401 |
| auth-service | 11 | global exception handling, registration ignores self-reported attributes (default low privilege), attribute updates admin-only (second gate behind USR-60) |
| risk-service | 3 | trading risk: single large transfer REVIEW, daily accumulation, fail-closed |
| agent-service | 4 | agent pre-check: external-link allowlist, dangerous commands, session counting, fail-closed |

The critical security chain (PEP auth → PDP decision → row-level filtering → fail-closed) has regression-test protection.

## Directory layout

```
spring-abac/
├── pom.xml                 # parent POM (9 modules, Spring Boot 3.2.5 / Cloud 2023.0.3)
├── eureka-server/          # 8762
├── config-server/          # 8889 + config-repo/*.yml
├── gateway-service/        # 4110  PEP
├── auth-service/           # 4111  identity + subject attributes
├── abac-service/           # 4112  PDP + SpEL engine + PIP
├── document-service/       # 4113  business domain + row-level filtering
├── audit-service/          # 4114  audit
├── risk-service/           # 4115  trading risk control (order pre-check + REVIEW manual review)
├── agent-service/          # 4116  agent pre-check (tool pre-check + REVIEW manual review)
├── web/                    # 3001  Next.js
├── scripts/demo.sh         # end-to-end curl demo
├── docker-compose.yml
├── k8s/spring-abac.yaml
├── docs/adr/               # architecture decision records
├── ARCHITECTURE.md
├── README.md
└── README.zh.md            # 中文版
```

## Privacy & compliance baseline (demo scope)

- **Registration collects no attributes**: subject attributes are the authorization basis and
  **cannot be self-reported** (otherwise anyone could register with `clearance=5, title=admin`
  and become an admin). New accounts default to low privilege (ENG / clearance 1 / CN / engineer);
  elevation is admin-only.
- **Attribute changes are admin-only**: gateway policy `USR-60` (USER/UPDATE admin-only) is the
  first gate, and auth-service re-checks the caller's `title == 'admin'` in the business layer as
  the second — even direct in-network calls cannot tamper with attributes.
- **JWT attributes are a snapshot**: after an attribute change the target user's old token keeps
  the old attributes until expiry (default 24h) — re-login applies the new ones. Production would
  need an attribute version / token blacklist.
- **Audit write protection**: the gateway → audit `X-Internal-Audit` header value is no longer
  hard-coded; both sides read `APP_INTERNAL_SECRET` from config (default
  dev-only-internal-secret-change-me).
- **Demo data**: username + PBKDF2 password hash + department/clearance/region/title attributes,
  collected only to demonstrate authorization semantics. A production deployment must add its own
  privacy policy, consent mechanism and account-deletion endpoints to satisfy local privacy laws
  (e.g. PIPL in China).

## Notes

This is a demo project: the JWT secret defaults to `dev-only-secret-change-me-please` (demo only) —
**for production, set the `APP_JWT_SECRET` environment variable; no code change needed**.
The database is an H2 file DB with `ddl-auto: create` (rebuilt on every startup).
Production would replace these: **secret into a key management service, policies into a dedicated store with
versioning and approval, PDP with decision caching and batch endpoints, attribute sources wired to a real
directory service and business DB**.
