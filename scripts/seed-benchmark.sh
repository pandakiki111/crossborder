#!/usr/bin/env bash
# 주문 엑셀 시딩 성능 측정: 로그인 → 업로드 → 응답 시간·건수 출력.
# 단계별 시간은 oms 로그의 "주문 시딩 완료 ... phasesMs={...}" 줄에 남는다.
#
# 사전 준비 (README "주문 엑셀 시딩 > 성능 측정" 참고)
#   1) docker compose up -d, oms·auth 기동 (local 프로필 — 테스트브랜드 매핑·계정이 시딩돼 있어야 함)
#   2) ./gradlew :oms:generateSeedFile -Prows=10000 -Pprefix=P10K
#
# 사용: scripts/seed-benchmark.sh oms/build/seed/orders-10000.xlsx
set -euo pipefail

FILE=${1:?업로드할 xlsx 경로}
AUTH_URL=${AUTH_URL:-http://localhost:8081}
OMS_URL=${OMS_URL:-http://localhost:8082}
LOGIN_ID=${LOGIN_ID:-brand@test.local}
PASSWORD=${PASSWORD:-Test1234!}

TOKEN=$(curl -sf -X POST "$AUTH_URL/auth/login" -H 'Content-Type: application/json' \
  -d "{\"loginId\":\"$LOGIN_ID\",\"password\":\"$PASSWORD\"}" | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')
BRAND_ID=$(docker exec crossborder-mariadb mariadb -uapp -papppass crossborder -N \
  -e "SELECT brand_id FROM users WHERE login_id = '$LOGIN_ID'")

HEADERS=$(mktemp)
RESULT=$(mktemp --suffix=.xlsx 2>/dev/null || mktemp)
TIME=$(curl -sf -o "$RESULT" -D "$HEADERS" -w '%{time_total}' \
  -H "Authorization: Bearer $TOKEN" -F "file=@$FILE" -F "brandId=$BRAND_ID" "$OMS_URL/api/orders/seed")

echo "file=$(basename "$FILE") size=$(wc -c < "$FILE" | tr -d ' ')B http_total=${TIME}s"
grep -i '^x-seed-' "$HEADERS" | tr -d '\r' | tr '\n' ' '
echo
rm -f "$HEADERS" "$RESULT"
