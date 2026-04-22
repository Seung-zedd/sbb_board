/**
 * Phase 3 — 시나리오 A: 다중 조건 복합 검색 부하 테스트
 *
 * 목적: 100명의 VU가 동시에 다중 조건 검색을 요청할 때
 *       커버링 인덱스 적용 전후 P95/P99 레이턴시 비교
 *
 * 실행 (콘솔 출력):
 *   k6 run load-test/scenario-a.js
 *
 * 실행 (Prometheus 연동):
 *   K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write \
 *   k6 run --out experimental-prometheus-rw load-test/scenario-a.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const errorRate = new Rate('scenario_a_error_rate');
const searchDuration = new Trend('scenario_a_search_duration', true);

export const options = {
  stages: [
    { duration: '30s', target: 10 },   // 워밍업
    { duration: '60s', target: 50 },   // 부하 증가 (HikariCP 풀 포화 시작)
    { duration: '60s', target: 100 },  // 과부하 (한계선 관찰)
    { duration: '30s', target: 0 },    // 쿨다운
  ],
  thresholds: {
    'scenario_a_search_duration': ['p(95)<3000'],
    scenario_a_error_rate: ['rate<0.05'],
  },
};

const BASE_URL = 'http://localhost:8080';

// 실제 트래픽 패턴 반영:
// - 빈 검색(전체 목록)이 가장 많고
// - 키워드 검색이 간헐적으로 섞임
const SEARCH_SCENARIOS = [
  { kw: '', page: 0, weight: 30 },       // 전체 목록 첫 페이지 (가장 빈번)
  { kw: '', page: 1, weight: 15 },       // 전체 목록 2페이지
  { kw: '', page: 2, weight: 5 },        // 전체 목록 3페이지
  { kw: '자바', page: 0, weight: 10 },
  { kw: '스프링', page: 0, weight: 10 },
  { kw: 'JPA', page: 0, weight: 8 },
  { kw: '쿼리', page: 0, weight: 7 },
  { kw: '오류', page: 0, weight: 8 },
  { kw: '성능', page: 0, weight: 5 },
  { kw: '테스트', page: 0, weight: 2 },
];

// 가중치 기반 시나리오 선택
function pickScenario() {
  const total = SEARCH_SCENARIOS.reduce((sum, s) => sum + s.weight, 0);
  let rand = Math.random() * total;
  for (const s of SEARCH_SCENARIOS) {
    rand -= s.weight;
    if (rand <= 0) return s;
  }
  return SEARCH_SCENARIOS[0];
}

export default function () {
  const scenario = pickScenario();
  const url = `${BASE_URL}/question/list?page=${scenario.page}&kw=${encodeURIComponent(scenario.kw)}`;

  const res = http.get(url, {
    tags: {
      endpoint: 'question_list',
      kw_type: scenario.kw === '' ? 'full_scan' : 'keyword_search',
    },
  });

  const success = check(res, {
    'status 200': (r) => r.status === 200,
    'response < 3s': (r) => r.timings.duration < 3000,
  });

  errorRate.add(!success);
  searchDuration.add(res.timings.duration);

  sleep(Math.random() * 0.5);
}

export function handleSummary(data) {
  const dur = data.metrics.scenario_a_search_duration;
  const rps = data.metrics.http_reqs;
  const err = data.metrics.scenario_a_error_rate;

  const fmt = (v) => v != null ? v.toFixed(0) : 'N/A';
  const fmtRate = (v) => v != null ? v.toFixed(1) : 'N/A';

  return {
    stdout: `
==========================================================
  Phase 3 시나리오 A — 다중 조건 복합 검색
==========================================================
  총 요청 수:   ${rps?.values?.count ?? 'N/A'}
  TPS:          ${fmtRate(rps?.values?.rate)} req/s
  P50:          ${fmt(dur?.values?.['p(50)'])}ms
  P95:          ${fmt(dur?.values?.['p(95)'])}ms
  P99:          ${fmt(dur?.values?.['p(99)'])}ms
  최소/최대:    ${fmt(dur?.values?.min)}ms / ${fmt(dur?.values?.max)}ms
  에러율:       ${err ? (err.values.rate * 100).toFixed(2) : '0.00'}%
==========================================================
`,
  };
}
