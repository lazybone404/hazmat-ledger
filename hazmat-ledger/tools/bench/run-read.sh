#!/usr/bin/env bash
# 读路径压测编排。
#
# 前置条件：
#   1. 应用以压测模式启动（会装载大数据集，默认关闭，不显式打开不执行）：
#        ./mvnw spring-boot:run \
#          -Dspring-boot.run.jvmArguments="-Xmx4g" \
#          -Dspring-boot.run.arguments="--hazmat.bench.enabled=true \
#            --hazmat.seed.enabled=false --hazmat.ai.encode-on-startup=false \
#            --hazmat.bench.chemicals=10000 --hazmat.bench.requisitions=20000"
#   2. token 放进 /tmp/bench-token（脚本不保存账号密码）：
#        curl -s -X POST http://localhost:8090/api/auth/login \
#          -H "Content-Type: application/json" \
#          -d '{"username":"admin","password":"ChangeMe123!"}' \
#          | python -c "import sys,json;sys.stdout.write(json.load(sys.stdin)['data']['token'])" \
#          > /tmp/bench-token
#
# 可调环境变量：BASE_URL、JAVA_BIN、CONCURRENCY、SECONDS_PER_CASE。
# 每项一个独立 JVM（JDK 21 单文件源码模式），避免上一项的 JIT 状态与连接复用到下一项。
set -u

B=${BASE_URL:-http://localhost:8090}
JAVA=${JAVA_BIN:-java}
TOOL_DIR=$(cd "$(dirname "$0")" && pwd)
# Windows 下写出的 token 文件常带 CRLF，回车留在 HTTP 头里会被 Tomcat 判为 400
TOKEN=$(tr -d '\r\n' < /tmp/bench-token)
CONC=${CONCURRENCY:-32}
SECS=${SECONDS_PER_CASE:-15}

run() {
    "$JAVA" "$TOOL_DIR/Bench.java" "$@" 2>&1
}

run "chemicals-list"   GET "$B/api/chemicals?page=0&size=20"                          "$CONC" "$SECS" "$TOKEN"
run "chemicals-keyword" GET "$B/api/chemicals?keyword=BENCH-&page=0&size=20"           "$CONC" "$SECS" "$TOKEN"
run "chemicals-stock"  GET "$B/api/chemicals/1000/stock"                               "$CONC" "$SECS" "$TOKEN"
run "chemicals-ledger" GET "$B/api/chemicals/1000/ledger?page=0&size=20"              "$CONC" "$SECS" "$TOKEN"
run "chemicals-expiring" GET "$B/api/chemicals/expiring?withinDays=30&page=0&size=20" "$CONC" "$SECS" "$TOKEN"
run "reports-summary"  GET "$B/api/reports/stock-summary"                              "$CONC" "$SECS" "$TOKEN"
run "reconcile"        GET "$B/api/chemicals/1000/reconcile"                           "$CONC" "$SECS" "$TOKEN"
run "requisitions"     GET "$B/api/requisitions?applicant=user1&page=0&size=20"        "$CONC" "$SECS" "$TOKEN"
