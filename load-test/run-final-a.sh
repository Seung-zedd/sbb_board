#!/bin/bash
# scenario-a.js 최종 재측정 — 1회분. 회차 번호를 인자로 받는다.
#
# 사용: bash load-test/run-final-a.sh 1
# 출력: results/final_a_1.txt
#
# 회차마다 앱을 완전히 내렸다 올린다. JIT 상태와 커넥션 풀을 동일 조건에서 출발시키기 위해서다.
#
# [주의] scenario-a.js의 handleSummary는 파일을 쓰지 않고 { stdout }만 반환한다.
#        게다가 handleSummary를 정의하면 k6 기본 요약이 통째로 대체되어
#        http_req_duration / http_req_failed 블록이 아예 출력되지 않는다.
#        따라서 tee로 받아야 하고, 저장되는 건 커스텀 지표 블록뿐이다.
#
# [주의] scenario-a.js는 http://localhost:8080 을 하드코딩한다 (env 오버라이드 없음).

set -uo pipefail

N="${1:?회차 번호를 넘겨주세요 (예: bash load-test/run-final-a.sh 1)}"
# 2번째 인자로 빌드를 지정한다. 기본은 JOIN 전환본.
#   old = results/sbb-perf.jar (N+1 제거까지, 키워드 검색은 IN 서브쿼리)
# 같은 밤 같은 호스트에서 두 빌드를 각각 반복 측정해 분포를 비교하기 위한 것이다.
# 쌍(pair) 비교가 아니라 단일 빌드 반복이므로 쌍 상관 문제가 없다.
BUILD="${2:-join}"
JAVA="/c/Program Files/Java/jdk-21/bin/java.exe"
K6="/c/Program Files/k6/k6.exe"

if [ "$BUILD" = "old" ]; then
  JAR="results/sbb-perf.jar"
  OUT="results/final_a_old_${N}.txt"
  LOG="results/app_final_a_old_${N}.log"
else
  JAR="results/sbb-final-join.jar"
  OUT="results/final_a_${N}.txt"
  LOG="results/app_final_a_${N}.log"
fi
echo "[${N}회차] 빌드=${BUILD} jar=${JAR}"

echo "[${N}회차] 기존 java 프로세스 정리"
taskkill //F //IM java.exe >/dev/null 2>&1
sleep 3

echo "[${N}회차] 앱 기동 (8080, loadtest 프로파일)"
nohup "$JAVA" -jar "$JAR" --spring.profiles.active=loadtest > "$LOG" 2>&1 &

echo "[${N}회차] 기동 대기"
ready=0
for i in $(seq 1 180); do
  code=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/question/list 2>/dev/null || echo 000)
  if [ "$code" = "200" ]; then
    echo "[${N}회차] ${i}초 만에 200 응답"
    ready=1
    break
  fi
  sleep 1
done
if [ "$ready" -ne 1 ]; then
  echo "[${N}회차] 기동 실패 — 중단"
  exit 1
fi

# 기동 직후 JIT이 덜 데워진 상태를 피한다.
# scenario-a.js 자체가 30s@10VU 워밍업 스테이지를 갖고 있지만, 그 구간까지
# 클래스 로딩에 쓰이면 워밍업이 워밍업 노릇을 못 한다.
echo "[${N}회차] 사전 워밍업 20초"
warm_end=$((SECONDS + 20))
while [ $SECONDS -lt $warm_end ]; do
  curl -s -o /dev/null "http://localhost:8080/question/list?page=0&kw="
  curl -s -o /dev/null "http://localhost:8080/question/list?page=0&kw=%EC%9E%90%EB%B0%94"
done

echo "[${N}회차] k6 실행 (3분)"
"$K6" run load-test/scenario-a.js 2>&1 | tee "$OUT"

echo "[${N}회차] 앱 종료"
taskkill //F //IM java.exe >/dev/null 2>&1

echo "[${N}회차] 완료 -> ${OUT}"
