# spring-abac — ABAC 微服务系统（Spring Boot + Spring Cloud）便捷命令入口
#
# 九个服务（启动顺序固定：先基础设施，后业务）：
#   eureka-server     端口 8762  服务注册中心
#   config-server     端口 8889  配置中心（native 后端）
#   gateway-service   端口 4110  API 网关 / PEP（JWT 校验 + 映射动作 + 问 PDP + 审计）
#   auth-service      端口 4111  认证（注册/登录/签发带主体属性的 JWT）
#   abac-service      端口 4112  ABAC PDP（策略 CRUD + SpEL 求值 + PIP 回源）
#   document-service  端口 4113  文档业务域（行级 ABAC 过滤 + PIP 属性端点）
#   audit-service     端口 4114  审计（append-only，记录每次裁决与命中策略）
#   risk-service      端口 4115  交易风控（REVIEW 第三态：大额转人工复核 + 当日累计）
#   agent-service     端口 4116  AI Agent 前置校验（工具级策略 + 会话计数 + REVIEW）
# 外加前端：
#   web               端口 3001  Next.js（BFF：/api/* 经 rewrite 代理到网关 4110）
#
# 与 spring-rbac（端口块 8761/8888/41xx、前端 3000）完全错开，可同时运行互不干扰。
#
# 用法：make <target>，默认 make help

SHELL := /bin/bash
.DEFAULT_GOAL := help

MVN  := mvn
JAVA := java
JAVA_OPTS := -Xmx256m

EUREKA_JAR := eureka-server/target/eureka-server-0.0.1-SNAPSHOT.jar
CONFIG_JAR := config-server/target/config-server-0.0.1-SNAPSHOT.jar
GW_JAR     := gateway-service/target/gateway-service-0.0.1-SNAPSHOT.jar
AUTH_JAR   := auth-service/target/auth-service-0.0.1-SNAPSHOT.jar
ABAC_JAR   := abac-service/target/abac-service-0.0.1-SNAPSHOT.jar
DOC_JAR    := document-service/target/document-service-0.0.1-SNAPSHOT.jar
AUDIT_JAR  := audit-service/target/audit-service-0.0.1-SNAPSHOT.jar
RISK_JAR   := risk-service/target/risk-service-0.0.1-SNAPSHOT.jar
AGENT_JAR  := agent-service/target/agent-service-0.0.1-SNAPSHOT.jar

LOG_DIR := logs
PID_DIR := .pids

# --- 前端（Next.js，裸 jar 模式下以 dev server 方式随 make start 一起跑）---
WEB_DIR     := web
WEB_PORT    ?= 3001
WEB_BACKEND ?= http://localhost:4110
# 剥掉 NODE_OPTIONS 再跑 npm：注入型 NODE_OPTIONS（沙箱 fs shim / --use-system-ca 等）
# 会让 Next 构建/worker 起不来（实测 next build 报 mkdir EEXIST、Turbopack Worker 挂）。
NPM         := env -u NODE_OPTIONS npm
# make start/stop 是否带前端；WITH_WEB=0 则只跑后端九服务
# （注意：变量值行不要写行尾注释，make 会把注释前的空格算进变量值）
WITH_WEB    ?= 1
# 递归调用自身时用 $(SUBMAKE) 而非 $(MAKE)：GNU make 把含字面量 $(MAKE) 的命令行当作递归行，
# 即使 make -n（dry-run）也照样真执行 —— 那会让"只想预览"变成把服务真的启起来。
SUBMAKE     := $(MAKE)

.PHONY: help build compile test start dev stop restart status demo reset-db clean \
        web-install web-spawn web-wait web-start web-stop web-restart web-killport \
        docker-build docker-up docker-start docker-stop docker-down docker-reset \
        docker-logs docker-ps docker-demo \
        k3s-build k3s-ensure k3s-apply k3s-deploy k3s-status k3s-demo k3s-clean

help: ## 显示本帮助
	@echo "spring-abac — ABAC 微服务系统（Spring Boot + Spring Cloud）可用命令："
	@echo ""
	@echo "  make build     编译打包，生成九个可执行 jar（mvn package -DskipTests）"
	@echo "  make compile   仅编译（不打包）"
	@echo "  make test      跑全部单测（六模块 63 个用例）"
	@echo "  make start     后台启动九服务（eureka→config→auth/abac/document/audit/risk/agent/gateway）+ 前端 :3001"
	@echo "  make dev       重新编译并后台启动（改码后用）"
	@echo "  make stop      停止全部后台服务（含前端）"
	@echo "  make restart   停止 + 重新编译打包 + 启动（改码后必用，确保吃到新 jar）"
	@echo "  make status    检查九个后端服务 + 前端的健康/可达状态"
	@echo "  make demo      端到端演示（需先 make start）：文档/交易/Agent 三域裁决"
	@echo "  make reset-db  清空本地 H2 数据库（下次启动重建种子）"
	@echo "  make clean     停止服务 + mvn clean + 清空数据库 + 清 web/.next"
	@echo ""
	@echo "  make web-start    单独启动前端 dev server（:3001）"
	@echo "  make web-stop     单独停止前端"
	@echo "  make web-restart  重启前端"
	@echo "  （只跑后端: make start WITH_WEB=0）"
	@echo ""
	@echo "  make docker-build  先 make build 再构建全部 Docker 镜像"
	@echo "  make docker-up     构建并后台启动全部服务（compose up -d --build）"
	@echo "  make docker-stop   停止并移除容器（保留数据卷）"
	@echo "  make docker-reset  停止并删除数据卷（清空 H2 数据库）"
	@echo "  make docker-ps     查看容器状态"
	@echo "  make docker-logs   跟踪查看容器日志"
	@echo "  make docker-demo   容器启动后经网关跑端到端演示"
	@echo ""
	@echo "  make k3s-build     构建镜像到节点本地"
	@echo "  make k3s-deploy    部署到 k3s（namespace abac-demo）"
	@echo "  make k3s-status    查看 k3s 下 Pod / Service"
	@echo "  make k3s-demo      端口转发网关并跑 ABAC 演示"
	@echo "  make k3s-clean     从 k3s 清理（删除 namespace abac-demo）"
	@echo ""
	@echo "提示：先 make start，浏览器开 http://localhost:3001；或另开终端 make demo 看纯 curl 链路。"
	@echo "      演示账号 admin/admin123（EXEC·5·CN·admin）、carol/carol123（ENG·4·US·manager）、"
	@echo "      alice/alice123（ENG·3·CN·engineer）、bob/bob123（SALES·2·CN·sales）——属性不同裁决不同。"

build: ## 编译打包，生成九个可执行 jar
	$(MVN) -q package -DskipTests

compile: ## 仅编译（不打包）
	$(MVN) -q compile

test: ## 跑全部单测（PDP 引擎 / 行级过滤 / PEP 认证边界 / 风控 / Agent）
	$(MVN) test

start: ## 后台启动九服务 + 前端，等待就绪
	@if [ ! -f $(EUREKA_JAR) ] || [ ! -f $(CONFIG_JAR) ] || [ ! -f $(GW_JAR) ] || [ ! -f $(AUTH_JAR) ] || [ ! -f $(ABAC_JAR) ] || [ ! -f $(DOC_JAR) ] || [ ! -f $(AUDIT_JAR) ] || [ ! -f $(RISK_JAR) ] || [ ! -f $(AGENT_JAR) ]; then echo "jar 缺失，先编译..."; $(SUBMAKE) build; fi
	@mkdir -p $(LOG_DIR) $(PID_DIR)
	@env -u SERVER__PORT -u SERVER_PORT nohup $(JAVA) $(JAVA_OPTS) -jar $(EUREKA_JAR) > $(LOG_DIR)/eureka.log 2>&1 & echo $$! > $(PID_DIR)/eureka.pid
	@echo "  启动 eureka-server     (8762)，PID $$(cat $(PID_DIR)/eureka.pid)"
	@env -u SERVER__PORT -u SERVER_PORT nohup $(JAVA) $(JAVA_OPTS) -jar $(CONFIG_JAR) > $(LOG_DIR)/config.log 2>&1 & echo $$! > $(PID_DIR)/config.pid
	@echo "  启动 config-server     (8889)，PID $$(cat $(PID_DIR)/config.pid)"
	@echo "等待基础设施就绪（eureka 8762 / config 8889）..."
	@for i in $$(seq 1 60); do \
	   eu=$$(curl -s --noproxy 127.0.0.1,localhost -o /dev/null -w "%{http_code}" --max-time 2 http://127.0.0.1:8762/eureka/apps 2>/dev/null); \
	   cf=$$(curl -s --noproxy 127.0.0.1,localhost -o /dev/null -w "%{http_code}" --max-time 2 http://127.0.0.1:8889/actuator/health 2>/dev/null); \
	   if [ "$$eu" != "000" ] && [ "$$cf" != "000" ]; then echo "  基础设施就绪 ✅ (eureka=$$eu config=$$cf)"; break; fi; \
	   sleep 1; \
	   if [ $$i -eq 60 ]; then echo "  超时未就绪，查看日志: tail -f $(LOG_DIR)/eureka.log $(LOG_DIR)/config.log"; fi; \
	 done
	@env -u SERVER__PORT -u SERVER_PORT nohup $(JAVA) $(JAVA_OPTS) -jar $(AUTH_JAR) > $(LOG_DIR)/auth.log 2>&1 & echo $$! > $(PID_DIR)/auth.pid
	@echo "  启动 auth-service      (4111)，PID $$(cat $(PID_DIR)/auth.pid)"
	@env -u SERVER__PORT -u SERVER_PORT nohup $(JAVA) $(JAVA_OPTS) -jar $(ABAC_JAR) > $(LOG_DIR)/abac.log 2>&1 & echo $$! > $(PID_DIR)/abac.pid
	@echo "  启动 abac-service      (4112)，PID $$(cat $(PID_DIR)/abac.pid)"
	@env -u SERVER__PORT -u SERVER_PORT nohup $(JAVA) $(JAVA_OPTS) -jar $(DOC_JAR) > $(LOG_DIR)/document.log 2>&1 & echo $$! > $(PID_DIR)/document.pid
	@echo "  启动 document-service  (4113)，PID $$(cat $(PID_DIR)/document.pid)"
	@env -u SERVER__PORT -u SERVER_PORT nohup $(JAVA) $(JAVA_OPTS) -jar $(AUDIT_JAR) > $(LOG_DIR)/audit.log 2>&1 & echo $$! > $(PID_DIR)/audit.pid
	@echo "  启动 audit-service     (4114)，PID $$(cat $(PID_DIR)/audit.pid)"
	@env -u SERVER__PORT -u SERVER_PORT nohup $(JAVA) $(JAVA_OPTS) -jar $(RISK_JAR) > $(LOG_DIR)/risk.log 2>&1 & echo $$! > $(PID_DIR)/risk.pid
	@echo "  启动 risk-service      (4115)，PID $$(cat $(PID_DIR)/risk.pid)"
	@env -u SERVER__PORT -u SERVER_PORT nohup $(JAVA) $(JAVA_OPTS) -jar $(AGENT_JAR) > $(LOG_DIR)/agent.log 2>&1 & echo $$! > $(PID_DIR)/agent.pid
	@echo "  启动 agent-service     (4116)，PID $$(cat $(PID_DIR)/agent.pid)"
	@env -u SERVER__PORT -u SERVER_PORT nohup $(JAVA) $(JAVA_OPTS) -jar $(GW_JAR) > $(LOG_DIR)/gateway.log 2>&1 & echo $$! > $(PID_DIR)/gateway.pid
	@echo "  启动 gateway-service   (4110)，PID $$(cat $(PID_DIR)/gateway.pid)"
	@echo "等待业务服务就绪..."
	@for i in $$(seq 1 60); do \
	   gw=$$(curl -s --noproxy 127.0.0.1,localhost -o /dev/null -w "%{http_code}" --max-time 2 http://127.0.0.1:4110/health 2>/dev/null); \
	   au=$$(curl -s --noproxy 127.0.0.1,localhost -o /dev/null -w "%{http_code}" --max-time 2 -X POST http://127.0.0.1:4111/api/login -H 'content-type: application/json' -d '{"username":"x","password":"y"}' 2>/dev/null); \
	   ab=$$(curl -s --noproxy 127.0.0.1,localhost -o /dev/null -w "%{http_code}" --max-time 2 http://127.0.0.1:4112/api/policies 2>/dev/null); \
	   dc=$$(curl -s --noproxy 127.0.0.1,localhost -o /dev/null -w "%{http_code}" --max-time 2 http://127.0.0.1:4113/api/documents 2>/dev/null); \
	   if [ "$$gw" = "200" ] && [ "$$au" != "000" ] && [ "$$ab" != "000" ] && [ "$$dc" != "000" ]; then echo "  业务就绪 ✅ (gateway=$$gw auth=$$au abac=$$ab document=$$dc)"; break; fi; \
	   sleep 1; \
	   if [ $$i -eq 60 ]; then echo "  超时未全部就绪，查看日志: tail -f $(LOG_DIR)/*.log"; fi; \
	 done
	@if [ "$(WITH_WEB)" = "1" ]; then $(SUBMAKE) --no-print-directory web-spawn; else echo "  跳过前端（WITH_WEB=0）"; fi
	@sleep 35; echo "  额外等待 35s，确保 Eureka 注册表传播与网关负载均衡缓存刷新（lb 解析就绪）"
	@if [ "$(WITH_WEB)" = "1" ]; then $(SUBMAKE) --no-print-directory web-wait; fi

dev: ## 重新编译并后台启动（改码后用）
	$(SUBMAKE) build
	$(SUBMAKE) start

stop: ## 停止全部后台服务（含前端）
	@echo "停止服务..."
	@for s in eureka config auth abac document audit risk agent gateway; do \
	   if [ -f $(PID_DIR)/$$s.pid ]; then kill $$(cat $(PID_DIR)/$$s.pid) 2>/dev/null || true; rm -f $(PID_DIR)/$$s.pid; fi; \
	 done
	@pkill -f "eureka-server-0.0.1-SNAPSHOT.jar" 2>/dev/null || true
	@pkill -f "config-server-0.0.1-SNAPSHOT.jar" 2>/dev/null || true
	@pkill -f "auth-service-0.0.1-SNAPSHOT.jar" 2>/dev/null || true
	@pkill -f "abac-service-0.0.1-SNAPSHOT.jar" 2>/dev/null || true
	@pkill -f "document-service-0.0.1-SNAPSHOT.jar" 2>/dev/null || true
	@pkill -f "audit-service-0.0.1-SNAPSHOT.jar" 2>/dev/null || true
	@pkill -f "risk-service-0.0.1-SNAPSHOT.jar" 2>/dev/null || true
	@pkill -f "agent-service-0.0.1-SNAPSHOT.jar" 2>/dev/null || true
	@pkill -f "gateway-service-0.0.1-SNAPSHOT.jar" 2>/dev/null || true
	@if [ "$(WITH_WEB)" = "1" ]; then $(SUBMAKE) --no-print-directory web-stop; fi
	@echo "已停止。"

restart: stop build start ## 停止、重新编译打包、再启动（改码后必用，确保吃到新 jar）

# ------------------------------------------------------------
# 前端（Next.js dev server）—— 裸 jar 模式的配套目标
# 容器化模式（compose / k3s）里前端由镜像跑，不走这里。
# ------------------------------------------------------------
web-install: ## 安装前端依赖（node_modules 缺失时才装）
	@if [ ! -d $(WEB_DIR)/node_modules ]; then \
	   echo "  前端依赖缺失，安装中（npm install，首次较慢）..."; \
	   (cd $(WEB_DIR) && $(NPM) install --no-audit --no-fund) || { echo "  ⚠️ npm install 失败，跳过前端"; exit 0; }; \
	 fi

web-build: ## 生产构建前端（next build，验证可上线；需先 web-install）
	@(cd $(WEB_DIR) && $(NPM) run build)

web-typecheck: ## 前端类型检查（tsc --noEmit）
	@(cd $(WEB_DIR) && $(NPM) run typecheck)

web-spawn: ## 仅后台拉起前端进程（先强制释放端口并清缓存，供 make start 并行用）
	@if ! command -v npm >/dev/null 2>&1; then echo "  未检测到 npm，跳过前端（装好 Node.js 后再 make web-start）"; exit 0; fi
	@$(SUBMAKE) --no-print-directory web-killport
	@rm -rf $(WEB_DIR)/.next
	@$(SUBMAKE) --no-print-directory web-install
	@mkdir -p $(LOG_DIR) $(PID_DIR)
	@(cd $(WEB_DIR) && BACKEND_URL=$(WEB_BACKEND) nohup $(NPM) run dev -- -p $(WEB_PORT) > $(CURDIR)/$(LOG_DIR)/web.log 2>&1 & echo $$! > $(CURDIR)/$(PID_DIR)/web.pid)
	@echo "  启动 web (Next.js dev)  ($(WEB_PORT))，PID $$(cat $(PID_DIR)/web.pid)，后端 $(WEB_BACKEND)"

web-wait: ## 等待前端就绪（GET /login => 200）
	@if [ ! -f $(PID_DIR)/web.pid ] && [ "$$(curl -s --noproxy 127.0.0.1,localhost -o /dev/null -w '%{http_code}' --max-time 2 http://127.0.0.1:$(WEB_PORT)/login 2>/dev/null)" = "000" ]; then exit 0; fi
	@echo "等待前端就绪（http://localhost:$(WEB_PORT)/login）..."
	@for i in $$(seq 1 90); do \
	   c=$$(curl -s --noproxy 127.0.0.1,localhost -o /dev/null -w "%{http_code}" --max-time 2 http://127.0.0.1:$(WEB_PORT)/login 2>/dev/null); \
	   if [ "$$c" = "200" ]; then echo "  前端就绪 ✅  http://localhost:$(WEB_PORT)"; break; fi; \
	   sleep 1; \
	   if [ $$i -eq 90 ]; then echo "  前端超时未就绪（HTTP=$${c}），查看日志: tail -f $(LOG_DIR)/web.log"; fi; \
	 done

web-start: web-spawn web-wait ## 单独启动前端并等待就绪

web-stop: ## 停止前端 dev server
	@if [ -f $(PID_DIR)/web.pid ]; then kill $$(cat $(PID_DIR)/web.pid) 2>/dev/null || true; rm -f $(PID_DIR)/web.pid; fi
	@$(SUBMAKE) --no-print-directory web-killport
	@echo "  前端已停止（:$(WEB_PORT)）"

web-killport: ## 强制释放前端端口（杀掉占用 WEB_PORT 的任意进程）
	@pids=$$(lsof -ti tcp:$(WEB_PORT) -sTCP:LISTEN 2>/dev/null); \
	 if [ -n "$$pids" ]; then \
	   echo "  释放 :$(WEB_PORT)（杀掉占用进程: $${pids}）"; \
	   echo "$$pids" | xargs -r kill -9 2>/dev/null || true; \
	   sleep 1; \
	 else \
	   echo "  :$(WEB_PORT) 当前未被占用"; \
	 fi

web-restart: web-stop web-start ## 重启前端

status: ## 检查九个后端服务 + 前端的健康/可达状态
	@for spec in "8762 GET /eureka/apps" "8889 GET /actuator/health" "4110 GET /health" "4111 POST /api/login" "4112 GET /api/policies" "4113 GET /api/documents" "4114 GET /api/audit" "4115 GET /api/trades/reviews" "4116 GET /api/agent/reviews" "$(WEB_PORT) GET /login"; do \
	   port=$$(echo $$spec | cut -d' ' -f1); \
	   method=$$(echo $$spec | cut -d' ' -f2); \
	   path=$$(echo $$spec | cut -d' ' -f3); \
	   if [ "$$method" = "POST" ]; then \
	     code=$$(curl -s --noproxy 127.0.0.1,localhost -o /dev/null -w "%{http_code}" --max-time 2 -X POST http://127.0.0.1:$$port$$path -H 'content-type: application/json' -d '{"username":"x","password":"y"}' 2>/dev/null); \
	   else \
	     code=$$(curl -s --noproxy 127.0.0.1,localhost -o /dev/null -w "%{http_code}" --max-time 2 http://127.0.0.1:$$port$$path 2>/dev/null); \
	   fi; \
	   if [ "$$code" = "000" ]; then echo "  端口 $$port ($$method $$path): 未响应 ($$code)"; \
	   else echo "  端口 $$port ($$method $$path): 可达 ($$code)"; fi; \
	 done

demo: ## 端到端演示（需先 make start）
	@bash scripts/demo.sh

reset-db: ## 清空本地 H2 数据库（下次启动重建种子）
	@if [ -d data ]; then rm -rf data; echo "已清空 data/（下次启动重建种子）"; else echo "data/ 不存在，无需清理"; fi

clean: stop ## 停止服务 + 清理构建产物与数据库
	@rm -rf data
	@rm -rf $(WEB_DIR)/.next
	@$(MVN) -q clean
	@echo "清理完成（target/、data/、web/.next 已移除；web/node_modules 保留）。"

# ============================================================
# Docker Compose 容器化
# ============================================================
DOCKER_COMPOSE := docker compose

docker-build: build ## 先 make build 生成 jar，再构建全部 Docker 镜像
	@echo "构建 Docker 镜像（docker compose build）..."
	$(DOCKER_COMPOSE) build

docker-up: build ## 构建镜像并后台启动全部服务（compose up -d --build）
	@echo "docker compose up -d --build ..."
	$(DOCKER_COMPOSE) up -d --build
	@echo "已在后台启动。查看状态: make docker-ps；查看日志: make docker-logs"

docker-start: docker-up ## 同 docker-up

docker-stop: ## 停止并移除容器（保留数据卷）
	@echo "docker compose down ..."
	$(DOCKER_COMPOSE) down

docker-down: docker-stop ## 同 docker-stop

docker-reset: ## 停止并删除数据卷（清空 H2 数据库，下次启动重建种子）
	@echo "docker compose down -v ..."
	$(DOCKER_COMPOSE) down -v

docker-logs: ## 跟踪查看全部容器日志（Ctrl-C 退出）
	$(DOCKER_COMPOSE) logs -f

docker-ps: ## 查看容器状态
	$(DOCKER_COMPOSE) ps

docker-demo: docker-up ## 启动后经网关跑 ABAC 演示（等待容器就绪）
	@echo "等待 Eureka 注册 auth-service 实例（网关 lb 解析前置）..."
	@for i in $$(seq 1 60); do \
	   code=$$(curl -s --noproxy 127.0.0.1,localhost -o /dev/null -w "%{http_code}" --max-time 2 http://127.0.0.1:8762/eureka/apps/AUTH-SERVICE 2>/dev/null); \
	   if [ "$$code" = "200" ]; then echo "  Eureka 已注册 auth-service ✅"; break; fi; \
	   sleep 2; \
	   if [ $$i -eq 60 ]; then echo "  超时：Eureka 未注册 auth-service（诊断: make docker-logs）"; exit 1; fi; \
	 done
	@echo "等待网关可登录（POST /api/login => 200）..."
	@for i in $$(seq 1 60); do \
	   code=$$(curl -s --noproxy 127.0.0.1,localhost -o /dev/null -w "%{http_code}" --max-time 3 -X POST http://127.0.0.1:4110/api/login -H 'content-type: application/json' -d '{"username":"admin","password":"admin123"}' 2>/dev/null); \
	   if [ "$$code" = "200" ]; then echo "  网关就绪 ✅"; break; fi; \
	   if [ $$i -eq 60 ]; then echo "  网关登录超时（最后 HTTP=$${code}），诊断: make docker-logs"; exit 1; fi; \
	   sleep 2; \
	 done
	@bash scripts/demo.sh

# ============================================================
# k3s 部署（单节点 / OrbStack 友好）
# ============================================================
k3s-build: docker-build ## 构建镜像到节点本地

k3s-ensure:
	@if command -v orb >/dev/null 2>&1; then \
	  echo "确保 OrbStack Kubernetes 已启动..."; \
	  orb start k8s >/dev/null 2>&1 || true; \
	else \
	  echo "未检测到 orb CLI，跳过自动启动（非 OrbStack 环境请自行确保集群可达）。"; \
	fi
	@echo "等待 k3s apiserver 可达..."
	@for i in $$(seq 1 40); do \
	  if kubectl cluster-info >/dev/null 2>&1; then echo "  k3s 可达 ✅"; break; fi; \
	  sleep 3; \
	  if [ $$i -eq 40 ]; then echo "  ⚠️ 超时：k3s apiserver 仍不可达。请先 orb start k8s 或手动起 k3s"; exit 1; fi; \
	 done

k3s-apply k3s-deploy: k3s-ensure ## 部署到 k3s（namespace abac-demo）
	@kubectl apply -f k8s/spring-abac.yaml
	@echo "已部署。查看状态: make k3s-status"

k3s-status: ## 查看 k3s 下 Pod / Service 状态
	@kubectl -n abac-demo get pods,svc

k3s-demo: k3s-ensure ## 端口转发网关并跑 ABAC 演示
	@if ! kubectl cluster-info >/dev/null 2>&1; then \
	  echo "  ⚠️ kubectl 连不上 k3s API server。OrbStack 用户请先 orb start k8s"; exit 1; \
	fi
	@if ! kubectl -n abac-demo get svc gateway-service >/dev/null 2>&1; then \
	  echo "  ⚠️ abac-demo/gateway-service 不存在，请先 make k3s-deploy"; exit 1; \
	fi
	@echo "等待 Pod 全部 Running..."
	@for i in $$(seq 1 60); do \
	   total=$$(kubectl -n abac-demo get pods --no-headers 2>/dev/null | wc -l | tr -d ' '); \
	   running=$$(kubectl -n abac-demo get pods --no-headers 2>/dev/null | awk '$$3=="Running"' | wc -l | tr -d ' '); \
	   if [ "$$total" != "0" ] && [ "$$running" = "$$total" ]; then echo "  Pod 全部 Running ($$running/$$total) ✅"; break; fi; \
	   sleep 3; \
	   if [ $$i -eq 60 ]; then echo "  超时：Pod 未全部就绪，查看 make k3s-status"; exit 1; fi; \
	 done
	@echo "端口转发网关（本地 41100 → 集群 4110），运行 demo..."
	@kubectl -n abac-demo port-forward svc/gateway-service 41100:4110 & \
	  PFPID=$$!; \
	  for i in $$(seq 1 30); do \
	    gwc=$$(curl -s --noproxy 127.0.0.1,localhost -o /dev/null -w "%{http_code}" --max-time 2 http://127.0.0.1:41100/health 2>/dev/null); \
	    if [ "$$gwc" = "200" ]; then echo "  网关端口转发就绪 ✅"; break; fi; \
	    sleep 1; \
	    if [ $$i -eq 30 ]; then echo "  转发未就绪（gateway=$${gwc}）"; kill $$PFPID 2>/dev/null; exit 1; fi; \
	  done; \
	  DEMO_PORT=41100 bash scripts/demo.sh; RC=$$?; kill $$PFPID 2>/dev/null; exit $$RC

k3s-clean: ## 从 k3s 清理（删除 namespace abac-demo）
	@kubectl delete -f k8s/spring-abac.yaml
	@echo "已清理 abac-demo。"
