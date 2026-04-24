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

const errorRate = new Rate('scenario_b_error_rate');
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
    scenario_b_error_rate: ['rate<0.05'],
  },
};

const BASE_URL = 'http://localhost:8080';

export default function () {
  // 배치 사전 계산 결과 조회 (단순 인덱스 스캔)
  const trendingRes = http.get(`${BASE_URL}/question/trending`, {
    tags: { endpoint: 'trending_batch' },
  });

  const trendingOk = check(trendingRes, {
    'trending status 200': (r) => r.status === 200,
    'trending < 500ms': (r) => r.timings.duration < 500,
  });

  errorRate.add(!trendingOk);
  trendingDuration.add(trendingRes.timings.duration);

  sleep(Math.random() * 0.5);
}

export function handleSummary(data) {
  const batch = data.metrics.scenario_b_trending_duration;
  const rps = data.metrics.http_reqs;
  const err = data.metrics.scenario_b_error_rate;

  return {
    stdout: `
==========================================================
  Phase 3 시나리오 B — 주간 인기글 랭킹 피드 (배치 분리)
==========================================================
  총 요청 수:   ${rps?.values.count ?? 'N/A'}
  TPS:          ${rps?.values.rate.toFixed(1) ?? 'N/A'} req/s

  [배치 사전 계산 조회]
  P50:          ${batch?.values['p(50)'].toFixed(0) ?? 'N/A'}ms
  P95:          ${batch?.values['p(95)'].toFixed(0) ?? 'N/A'}ms
  P99:          ${batch?.values['p(99)'].toFixed(0) ?? 'N/A'}ms
  최소/최대:    ${batch?.values.min.toFixed(0) ?? 'N/A'}ms / ${batch?.values.max.toFixed(0) ?? 'N/A'}ms
  에러율:       ${err ? (err.values.rate * 100).toFixed(2) : '0.00'}%
==========================================================
`,
  };
}
