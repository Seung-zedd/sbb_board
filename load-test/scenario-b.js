/**
 * Phase 3 — 시나리오 B: 주간 인기글 랭킹 피드 부하 테스트
 *
 * 목적: @Scheduled 배치로 사전 계산된 trending_question 테이블을
 *       100 VU가 동시에 조회할 때 단순 인덱스 스캔의 성능 확인
 *       (실시간 복합 GROUP BY 대비 레이턴시 비교)
 *
 * 실행 (콘솔 출력):
 *   k6 run load-test/scenario-b.js
 *
 * 실행 (Prometheus 연동):
 *   K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write \
 *   k6 run --out experimental-prometheus-rw load-test/scenario-b.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// scenario-a.js와 같은 이유로 지표를 분리한다.
// 통합 지표는 "서버가 죽었다"와 "느리다"를 구분하지 못해 원인 추적을 막는다.
const errorRate = new Rate('scenario_b_error_rate'); // HTTP 실패만 (status != 200)
const slowRate = new Rate('scenario_b_slow_rate');   // SLO 위반만 (duration >= 500ms)
const trendingDuration = new Trend('scenario_b_trending_duration', true);
// 비교용: 실시간 집계 엔드포인트 (배치 도입 전 상태 시뮬레이션)
const realtimeDuration = new Trend('scenario_b_realtime_duration', true);

export const options = {
  stages: [
    { duration: '30s', target: 10 },
    { duration: '60s', target: 50 },
    { duration: '60s', target: 100 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    'scenario_b_trending_duration': ['p(95)<500'],   // 배치: P95 < 500ms 기대
    scenario_b_error_rate: ['rate<0.05'], // 서버 실패 5% 미만
    scenario_b_slow_rate: ['rate<0.05'],  // 500ms 초과 응답 5% 미만
  },
  // k6 기본 요약은 p(90)/p(95)만 계산해 P50이 N/A로 찍혔다.
  summaryTrendStats: ['min', 'med', 'avg', 'p(95)', 'p(99)', 'max'],
};

const BASE_URL = 'http://localhost:8080';

export default function () {
  // 배치 사전 계산 결과 조회 (단순 인덱스 스캔)
  const trendingRes = http.get(`${BASE_URL}/question/trending`, {
    tags: { endpoint: 'trending_batch' },
  });

  check(trendingRes, {
    'trending status 200': (r) => r.status === 200,
    'trending < 500ms': (r) => r.timings.duration < 500,
  });

  errorRate.add(trendingRes.status !== 200);
  slowRate.add(trendingRes.timings.duration >= 500);
  trendingDuration.add(trendingRes.timings.duration);

  sleep(Math.random() * 0.5);
}

export function handleSummary(data) {
  const batch = data.metrics.scenario_b_trending_duration;
  const rps = data.metrics.http_reqs;
  const err = data.metrics.scenario_b_error_rate;
  const slow = data.metrics.scenario_b_slow_rate;

  // 값이 없을 때 .toFixed()를 호출하면 요약 자체가 예외로 죽는다.
  // 측정을 다 해놓고 결과를 못 보는 상황을 막기 위해 널 가드를 먼저 건다.
  const fmt = (v) => v != null ? v.toFixed(0) : 'N/A';
  const fmtRate = (v) => v != null ? v.toFixed(1) : 'N/A';
  const pct = (m) => m ? (m.values.rate * 100).toFixed(2) : '0.00';

  return {
    stdout: `
==========================================================
  Phase 3 시나리오 B — 주간 인기글 랭킹 피드 (배치 분리)
==========================================================
  총 요청 수:   ${rps?.values?.count ?? 'N/A'}
  TPS:          ${fmtRate(rps?.values?.rate)} req/s

  [배치 사전 계산 조회]
  P50:          ${fmt(batch?.values?.med)}ms
  P95:          ${fmt(batch?.values?.['p(95)'])}ms
  P99:          ${fmt(batch?.values?.['p(99)'])}ms
  최소/최대:    ${fmt(batch?.values?.min)}ms / ${fmt(batch?.values?.max)}ms
  ----------------------------------------------------------
  에러율:       ${pct(err)}%   (status != 200 — 서버 실패)
  느린 응답율:  ${pct(slow)}%   (500ms 초과 — SLO 위반)
==========================================================
`,
  };
}
