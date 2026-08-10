#!/bin/bash
# 키워드 검색 STEP 1: IN (서브쿼리) vs JOIN (파생 테이블) 실측 비교
#
# mysql 클라이언트를 파이프로 쓰면 batch 모드라 "(0.00 sec)"가 안 찍힌다.
# SET profiling=1 + SHOW PROFILES로 세션 내부에서 statement별 duration을 받는다.
#
# 사용: bash load-test/bench-kw-join.sh [반복횟수]
# 출력: 키워드별 A(IN)/B(JOIN) 최소·중앙 duration (ms)
#
# 주의: 이 호스트는 간섭 에피소드를 일으키므로 최소값을 참값으로 본다
#       (NEXT_STEPS_2026-08-09.md "측정할 때 반드시 지킬 것" 2항).

set -euo pipefail

DOCKER="/c/Program Files/Docker/Docker/resources/bin/docker.exe"
CONTAINER="sbb-performance-test-db"
REPEAT="${1:-5}"

# 기본값은 scenario-ab.js가 실제로 쏘는 키워드 7종.
# KW 환경변수로 덮어쓸 수 있다 (공백 구분). 경계 조건 탐색용:
#   user1        — 작성자명 LIKE가 다수 매칭 (user1, user10, user100...) → 파생 테이블이 커진다
#   zzzznotfound — 매칭 0건
if [ -n "${KW:-}" ]; then
  read -r -a KEYWORDS <<< "$KW"
else
  KEYWORDS=("자바" "스프링" "JPA" "쿼리" "오류" "성능" "테스트")
fi

union_body() {
  local kw="$1"
  cat <<EOF
  SELECT q2.ID AS ID FROM QUESTION q2
   WHERE MATCH(q2.SUBJECT, q2.CONTENT) AGAINST ('${kw}' IN BOOLEAN MODE)
  UNION
  SELECT q3.ID FROM QUESTION q3 JOIN SITE_USER u1 ON q3.AUTHOR_ID = u1.ID
   WHERE u1.USERNAME LIKE '%${kw}%'
  UNION
  SELECT a.QUESTION_ID FROM ANSWER a JOIN SITE_USER u2 ON a.AUTHOR_ID = u2.ID
   WHERE u2.USERNAME LIKE '%${kw}%'
EOF
}

# profiling_history_size 상한이 100이라 전 키워드를 한 세션에 몰면 앞부분이 잘린다.
# 키워드 하나당 세션 하나로 분리한다 (3 variant x REPEAT <= 100).
build_sql_for() {
  local kw="$1"
  local body
  body="$(union_body "$kw")"
  echo "SET profiling = 1;"
  echo "SET profiling_history_size = 100;"
  local _
  for _ in $(seq 1 "$REPEAT"); do
    # A: 현재 — IN (서브쿼리)
    echo "SELECT /*A|${kw}*/ q.ID FROM QUESTION q WHERE q.ID IN (${body}) ORDER BY q.CREATE_DATE DESC LIMIT 10;"
    # B: 제안 — JOIN (파생 테이블)
    echo "SELECT /*B|${kw}*/ q.ID FROM QUESTION q JOIN (${body}) t ON t.ID = q.ID ORDER BY q.CREATE_DATE DESC LIMIT 10;"
    # C: countQuery — A/B 공통. Page<> 반환이라 매 요청 함께 나간다.
    #    요청 단위 실제 비용은 (A+C) vs (B+C)로 봐야 한다.
    echo "SELECT /*C|${kw}*/ COUNT(*) FROM (${body}) t;"
  done
  echo "SHOW PROFILES;"
}

# --comments 필수: 없으면 클라이언트가 /*A|kw*/ 마커를 떼버려 SHOW PROFILES에서 구분이 안 된다
for kw in "${KEYWORDS[@]}"; do
  build_sql_for "$kw" | "$DOCKER" exec -i "$CONTAINER" \
    mysql -usbbtest -ptest1234 --comments --batch --skip-column-names sbb_db 2>/dev/null
done \
| awk -F'\t' '
  # SHOW PROFILES 출력만 남는다: Query_ID \t Duration \t Query
  $2 ~ /^[0-9.]+$/ && $3 ~ /\/\*[ABC]\|/ {
    match($3, /\/\*([ABC])\|([^*]+)\*\//, m)
    key = m[2] "\t" m[1]
    ms = $2 * 1000
    n[key]++
    all[key, n[key]] = ms
  }
  END {
    printf "%-14s %-6s %8s %8s %8s\n", "keyword", "variant", "min_ms", "med_ms", "n"
    for (k in n) {
      cnt = n[k]
      for (i = 1; i <= cnt; i++) v[i] = all[k, i]
      # 삽입 정렬
      for (i = 2; i <= cnt; i++) { x = v[i]; j = i - 1; while (j > 0 && v[j] > x) { v[j+1] = v[j]; j-- } v[j+1] = x }
      split(k, parts, "\t")
      printf "%-14s %-6s %8.2f %8.2f %8d\n", parts[1], parts[2], v[1], v[int((cnt+1)/2)], cnt
      delete v
    }
  }
' | sort -k1,1 -k2,2
