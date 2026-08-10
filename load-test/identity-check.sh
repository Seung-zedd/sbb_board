#!/bin/bash
# A(8080)와 B(8081)의 HTTP 응답이 바이트 단위로 동일한지 확인한다.
#
# 성능 비교 전 필수 관문이다. 빨라졌는데 결과가 달라졌다면 최적화가 아니라 버그다.
# scenario-ab.js가 실제로 쏘는 조합(빈 검색 3종 + 키워드 7종)을 그대로 훑고,
# 매칭 0건 키워드와 페이지 경계도 추가로 본다.
#
# 사용: bash load-test/identity-check.sh [A_URL] [B_URL]

set -uo pipefail

A_URL="${1:-http://localhost:8080}"
B_URL="${2:-http://localhost:8081}"

# "kw|page" 형식. scenario-ab.js SCENARIOS + 경계 조건
CASES=(
  "|0" "|1" "|2"
  "자바|0" "스프링|0" "JPA|0" "쿼리|0" "오류|0" "성능|0" "테스트|0"
  "질문|0"          # FULLTEXT 0건 — A의 최악 케이스
  "zzzznotfound|0"  # 매칭 0건
  "user1|0"         # USERNAME LIKE 대량 매칭 — B의 최악 케이스
  "자바|1" "자바|5"  # 페이지 경계: LIMIT offset 경로도 같은지
)

fail=0
printf "%-16s %-5s %-8s %-8s %s\n" "kw" "page" "A" "B" "verdict"
for c in "${CASES[@]}"; do
  kw="${c%%|*}"
  page="${c##*|}"
  enc=$(printf '%s' "$kw" | od -An -tx1 | tr ' ' '%' | tr -d '\n' | tr -s '%')
  url="question/list?page=${page}&kw=${enc}"

  ma=$(curl -s "${A_URL}/${url}" | md5sum | cut -d' ' -f1)
  mb=$(curl -s "${B_URL}/${url}" | md5sum | cut -d' ' -f1)

  if [ "$ma" = "$mb" ]; then
    verdict="OK"
  else
    verdict="MISMATCH"
    fail=$((fail + 1))
  fi
  printf "%-16s %-5s %-8s %-8s %s\n" "${kw:-(empty)}" "$page" "${ma:0:8}" "${mb:0:8}" "$verdict"
done

echo
if [ "$fail" -eq 0 ]; then
  echo "전 케이스 응답 동일 (${#CASES[@]}건). 성능 비교로 진행 가능."
  exit 0
else
  echo "불일치 ${fail}건. 성능 비교를 진행하면 안 된다."
  exit 1
fi
