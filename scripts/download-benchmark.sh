#!/usr/bin/env bash
# 주문 다운로드 성능 측정: 로그인 → estimate → 다운로드 → 응답 시간·행 수 출력.
# 생성/쓰기 시간은 oms 로그의 "주문 다운로드 완료 ... generateMs=.. writeMs=.." 줄에 남는다.
#
# 사전 준비: docker compose up -d, oms·auth 기동 (local 프로필)
# 사용: scripts/download-benchmark.sh 'orderedFrom=2026-09-29&orderedTo=2026-09-29'
set -euo pipefail

QUERY=${1:?다운로드 조건 쿼리 문자열 (orderedFrom·orderedTo 필수)}
AUTH_URL=${AUTH_URL:-http://localhost:8081}
OMS_URL=${OMS_URL:-http://localhost:8082}
LOGIN_ID=${LOGIN_ID:-brand@test.local}
PASSWORD=${PASSWORD:-Test1234!}

TOKEN=$(curl -sf -X POST "$AUTH_URL/auth/login" -H 'Content-Type: application/json' \
  -d "{\"loginId\":\"$LOGIN_ID\",\"password\":\"$PASSWORD\"}" | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')

echo "estimate: $(curl -sf -H "Authorization: Bearer $TOKEN" "$OMS_URL/api/orders/download/estimate?$QUERY")"

HEADERS=$(mktemp)
RESULT=$(mktemp)
TIME=$(curl -s -o "$RESULT" -D "$HEADERS" -w '%{http_code} %{time_total}' \
  -H "Authorization: Bearer $TOKEN" "$OMS_URL/api/orders/download?$QUERY")

echo "status/http_total=${TIME}s size=$(wc -c < "$RESULT" | tr -d ' ')B $(grep -i '^x-download-count' "$HEADERS" | tr -d '\r')"
rm -f "$HEADERS" "$RESULT"
