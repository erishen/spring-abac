#!/usr/bin/env bash
# spring-abac 端到端演示：同一个接口，换主体属性即可得到不同裁决。
#
# 前置：make start（七服务 + 前端就绪）
# 用法：bash scripts/demo.sh          （默认打本机网关 4110）
#       DEMO_PORT=41100 bash scripts/demo.sh   （k3s 端口转发场景）
set -u

PORT="${DEMO_PORT:-4110}"
BASE="http://127.0.0.1:${PORT}"
CURL="curl -s --noproxy 127.0.0.1,localhost --max-time 5"

hr() { echo; echo "────────────────────────────────────────────────────────────"; echo "$1"; echo "────────────────────────────────────────────────────────────"; }

# 登录取 token（只取 JSON 里的 token 字段，省掉 jq 依赖）
login() {
  local user="$1" pass="$2"
  local resp
  resp=$($CURL -X POST "$BASE/api/login" -H 'content-type: application/json' \
          -d "{\"username\":\"$user\",\"password\":\"$pass\"}")
  echo "$resp" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p'
}

code() { # code <描述> <curl 输出文件> —— 只打印 HTTP 码
  echo "  $1 -> HTTP $(tail -1 "$2")"
}

hr "1) 登录四个属性不同的账号"
ADMIN=$(login admin admin123)
CAROL=$(login carol carol123)
ALICE=$(login alice alice123)
BOB=$(login bob bob123)
for pair in "admin:$ADMIN" "carol:$CAROL" "alice:$ALICE" "bob:$BOB"; do
  n="${pair%%:*}"; t="${pair#*:}"
  if [ -z "$t" ]; then echo "  ⚠️ $n 登录失败（服务未就绪？先 make start）"; else echo "  ✅ $n 已登录 (token ${#t} 字符)"; fi
done

hr "2) 各账号能看到的文档（行级 ABAC：列表按属性过滤）"
for pair in "admin:$ADMIN" "carol:$CAROL" "alice:$ALICE" "bob:$BOB"; do
  n="${pair%%:*}"; t="${pair#*:}"
  [ -z "$t" ] && continue
  body=$(mktemp)
  $CURL -w "\n%{http_code}" "$BASE/api/documents?page=0&size=50" \
        -H "Authorization: Bearer $t" > "$body"
  total=$(sed -n 's/.*"totalElements":\([0-9]*\).*/\1/p' "$body" | head -1)
  # macOS 的 cut -c 按字节切，会把中文腰斩成乱码；用 perl 按字符截断
  titles=$(grep -o '"title":"[^"]*"' "$body" | sed 's/"title":"//;s/"//' | paste -sd '、' - \
           | perl -CSD -ne 'print substr($_,0,88)')
  echo "  $n 可见 ${total:-0} 篇：$titles"
  rm -f "$body"
done

hr "3) 直接问 PDP：同一份资源，换主体看裁决翻转"
ask() { # ask <token> <username> <clearance> <region> <title>
  local t="$1" u="$2" c="$3" r="$4" ti="$5"
  local body
  body=$(cat <<JSON
{"subject":{"username":"$u","department":"ENG","clearance":$c,"region":"$r","title":"$ti"},
 "resource":{"type":"DOCUMENT","attributes":{"type":"DOCUMENT","owner":"alice","department":"ENG","classification":"CONFIDENTIAL","requiredClearance":4,"status":"DRAFT"}},
 "action":"READ","environment":{"hour":10}}
JSON
)
  echo "    $u (clearance=$c region=$r title=$ti) -> $(
    $CURL -X POST "$BASE/api/decide" -H 'content-type: application/json' \
          -H "Authorization: Bearer $t" -d "$body" \
    | sed 's/,"trace":.*//' | sed -n 's/.*"effect":"\([A-Z]*\)".*/\1/p'
  )"
}
echo "  读一篇 CONFIDENTIAL（需 clearance 4）的 ENG 文档："
[ -n "$ALICE" ] && ask "$ALICE" alice 3 CN engineer    # clearance 不足 -> DENY
[ -n "$CAROL" ] && ask "$CAROL" carol 4 US manager     # 密级够但在境外 -> DENY
[ -n "$CAROL" ] && ask "$CAROL" carol 4 CN manager     # 地区改 CN -> PERMIT
[ -n "$ADMIN" ] && ask "$ADMIN" admin 5 CN admin       # admin -> PERMIT

hr "4) deny-override：管理员也会被环境/地区策略挡住"
if [ -n "$ADMIN" ]; then
  body=$(cat <<'JSON'
{"subject":{"username":"admin","department":"EXEC","clearance":5,"region":"CN","title":"admin"},
 "resource":{"type":"DOCUMENT","attributes":{"type":"DOCUMENT","owner":"bob","department":"SALES","classification":"PUBLIC","requiredClearance":1,"status":"DRAFT"}},
 "action":"DELETE","environment":{"hour":22}}
JSON
)
  echo "  admin 在 22:00 删文档（P-100 非工作时间禁止删除，DENY 不翻案） -> $(
    $CURL -X POST "$BASE/api/decide" -H 'content-type: application/json' \
          -H "Authorization: Bearer $ADMIN" -d "$body" \
    | sed 's/,"trace":.*//' | sed -n 's/.*"effect":"\([A-Z]*\)".*/\1/p'
  )"
fi

hr "5) 策略域：谁能看 / 谁能改"
for pair in "admin:$ADMIN" "carol:$CAROL" "bob:$BOB"; do
  n="${pair%%:*}"; t="${pair#*:}"
  [ -z "$t" ] && continue
  c=$($CURL -o /dev/null -w "%{http_code}" "$BASE/api/policies" -H "Authorization: Bearer $t")
  echo "  $n GET /api/policies -> HTTP ${c}（manager/admin 才看得到，bob 应 403）"
done

hr "6) 审计：ADMIN 查看放行/拒绝统计"
if [ -n "$ADMIN" ]; then
  echo "  $($CURL "$BASE/api/audit/stats" -H "Authorization: Bearer $ADMIN")"
  echo "  最近 5 条（含命中策略）："
  $CURL "$BASE/api/audit?page=0&size=5" -H "Authorization: Bearer $ADMIN" \
    | grep -o '"actor":"[^"]*"[^}]*"action":"[^"]*","method":"[^"]*","path":"[^"]*"[^}]*"decision":"[^"]*","status":[^,]*,"policyId":[^,]*,"policyName":[^,]*' \
    | sed 's/"actor":/  actor=/;s/"action":/ action=/' \
    | perl -CSD -ne 'print substr($_,0,138); print "\n" unless /\n$/'
fi
if [ -n "$BOB" ]; then
  c=$($CURL -o /dev/null -w "%{http_code}" "$BASE/api/audit" -H "Authorization: Bearer $BOB")
  echo "  bob GET /api/audit -> HTTP ${c}（AUDIT:READ 只放 admin）"
fi

hr "演示结束"
echo "浏览器打开 http://localhost:3001 —— 「裁决模拟」页可随手改属性看裁决翻转。"
