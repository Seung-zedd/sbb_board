/**
 * P95 무릎(knee) 탐색 — open model 부하 시나리오
 *
 * 왜 별도 시나리오인가:
 *   scenario-a.js는 `stages`(ramping-vus) 기반의 closed model이다.
 *   VU는 "요청 -> 응답 대기 -> 다음 요청"을 반복하므로 서버가 느려지면 요청을 덜 보낸다.
 *   즉 TPS가 입력이 아니라 출력이 되고, TPS와 P95가 서로를 오염시킨다.
 *   A/B 비교를 하려면 "같은 입력(도착률)에서 지연이 어떻게 다른가"를 봐야 하므로
 *   도착률을 고정하는 constant-arrival-rate(open model)로 다시 만든다.
 *
 * 실행:
 *   RATE=30 k6 run load-test/scenario-knee.js
 *   RATE=30 DURATION=2m LABEL=baseline_r30 k6 run load-test/scenario-knee.js
 *
 * 환경변수:
 *   RATE      목표 도착률 (req/s). 기본 30
 *   DURATION  측정 구간 길이. 기본 '2m'
 *   VUS       사전 할당 VU 수. 기본 max(50, RATE * 5)
 *             -> RATE * 5는 "응답이 5초까지 늘어져도 VU 고갈로 인한 드롭은 안 나게" 하는 여유.
 *                이 여유가 없으면 dropped_iterations가 서버 포화가 아니라
 *                측정 도구의 한계를 재게 되어 무릎 판정이 틀어진다.
 *   LABEL     결과 파일 이름에 붙는 라벨. 기본 'knee_r{RATE}'
 *   BASE_URL  기본 http://localhost:8080
 */

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const RATE = Number(__ENV.RATE || 30);
const DURATION = __ENV.DURATION || '2m';
const VUS = Number(__ENV.VUS || Math.max(50, RATE * 5));
const LABEL = __ENV.LABEL || `knee_r${RATE}`;
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// 워밍업 구간이 지표를 오염시키지 않도록 custom metric은 measure 구간에서만 적는다.
// (http_req_duration 같은 내장 지표는 warmup을 포함하므로 요약에 쓰지 않는다)
const errorRate = new Rate('knee_error_rate'); // status != 200 — 서버 실패
const slowRate = new Rate('knee_slow_rate');   // >= 3000ms — SLO 위반
const duration = new Trend('knee_duration', true);
// Trend의 values.count는 k6 버전에 따라 노출되지 않아 실제 처리량이 0으로 찍혔다.
// 완료 건수는 Counter로 직접 센다.
const completed = new Counter('knee_completed');
// 전체 목록(빈 검색)과 키워드 검색은 쿼리 비용이 한 자릿수 배 차이난다.
// 섞인 P95만 보면 무엇이 무릎을 만드는지 알 수 없어 유형별로 분리한다.
const durFull = new Trend('knee_duration_full_scan', true);
const durKw = new Trend('knee_duration_keyword', true);

export const options = {
  scenarios: {
    // 20초 저부하 워밍업: JIT 컴파일 + 커넥션 풀 채우기 + 버퍼 풀 예열
    warmup: {
      executor: 'constant-arrival-rate',
      exec: 'warmup',
      rate: 5,
      timeUnit: '1s',
      duration: '20s',
      preAllocatedVUs: 10,
      maxVUs: 20,
      gracefulStop: '5s',
    },
    // 본 측정: 도착률 고정
    measure: {
      executor: 'constant-arrival-rate',
      exec: 'measure',
      startTime: '25s',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: VUS,
      maxVUs: VUS,
      gracefulStop: '10s',
    },
  },
  summaryTrendStats: ['min', 'med', 'avg', 'p(95)', 'p(99)', 'max'],
  // 무릎 탐색 중에는 threshold로 중단시키지 않는다.
  // 포화 지점을 "보는 것"이 목적이므로 실패해도 끝까지 측정해야 한다.
  thresholds: {},
};

// scenario-a.js와 동일한 트래픽 분포를 유지한다.
// 무릎 rate를 그대로 A/B에 쓸 것이므로 분포가 달라지면 비교가 깨진다.
const SEARCH_SCENARIOS = [
  { kw: '', page: 0, weight: 30 },
  { kw: '', page: 1, weight: 15 },
  { kw: '', page: 2, weight: 5 },
  { kw: '자바', page: 0, weight: 10 },
  { kw: '스프링', page: 0, weight: 10 },
  { kw: 'JPA', page: 0, weight: 8 },
  { kw: '쿼리', page: 0, weight: 7 },
  { kw: '오류', page: 0, weight: 8 },
  { kw: '성능', page: 0, weight: 5 },
  { kw: '테스트', page: 0, weight: 2 },
];

const TOTAL_WEIGHT = SEARCH_SCENARIOS.reduce((sum, s) => sum + s.weight, 0);

function pickScenario() {
  let rand = Math.random() * TOTAL_WEIGHT;
  for (const s of SEARCH_SCENARIOS) {
    rand -= s.weight;
    if (rand <= 0) return s;
  }
  return SEARCH_SCENARIOS[0];
}

function request(tagPhase) {
  const s = pickScenario();
  const url = `${BASE_URL}/question/list?page=${s.page}&kw=${encodeURIComponent(s.kw)}`;
  const res = http.get(url, {
    tags: {
      phase: tagPhase,
      endpoint: 'question_list',
      kw_type: s.kw === '' ? 'full_scan' : 'keyword_search',
    },
  });
  return { res, isKeyword: s.kw !== '' };
}

export function warmup() {
  request('warmup');
}

export function measure() {
  const { res, isKeyword } = request('measure');

  check(res, {
    'status 200': (r) => r.status === 200,
  });

  const ms = res.timings.duration;
  errorRate.add(res.status !== 200);
  slowRate.add(ms >= 3000);
  duration.add(ms);
  completed.add(1);
  (isKeyword ? durKw : durFull).add(ms);
}

export function handleSummary(data) {
  const d = data.metrics.knee_duration;
  const err = data.metrics.knee_error_rate;
  const slow = data.metrics.knee_slow_rate;
  const dropped = data.metrics.dropped_iterations;

  const v = (x) => (x != null ? x.toFixed(0) : 'N/A');
  const pct = (m) => (m ? (m.values.rate * 100).toFixed(2) : '0.00');

  const full = data.metrics.knee_duration_full_scan;
  const kw = data.metrics.knee_duration_keyword;
  const count = data.metrics.knee_completed?.values?.count ?? 0;
  const droppedCount = dropped?.values?.count ?? 0;
  // 목표 대비 실제 처리량. 서버가 못 따라오면 achieved < RATE 로 벌어진다.
  const achieved = count / toSeconds(DURATION);
  const dropPct = count + droppedCount > 0
    ? ((droppedCount / (count + droppedCount)) * 100).toFixed(2)
    : '0.00';

  const line = [
    LABEL,
    RATE,
    achieved.toFixed(1),
    v(d?.values?.med),
    v(d?.values?.['p(95)']),
    v(d?.values?.['p(99)']),
    v(d?.values?.max),
    pct(err),
    pct(slow),
    droppedCount,
    dropPct,
    v(full?.values?.['p(95)']),
    v(kw?.values?.['p(95)']),
  ].join('\t');

  const text = `
==========================================================
  P95 무릎 탐색 — ${LABEL}
==========================================================
  목표 도착률:    ${RATE} req/s   (측정 ${DURATION}, preAllocatedVUs ${VUS})
  실제 처리량:    ${achieved.toFixed(1)} req/s   (완료 ${count}건)
  ----------------------------------------------------------
  P50:            ${v(d?.values?.med)}ms
  P95:            ${v(d?.values?.['p(95)'])}ms
  P99:            ${v(d?.values?.['p(99)'])}ms
  최대:           ${v(d?.values?.max)}ms
  ----------------------------------------------------------
  전체목록 P50/P95: ${v(full?.values?.med)}ms / ${v(full?.values?.['p(95)'])}ms
  키워드검색 P50/P95: ${v(kw?.values?.med)}ms / ${v(kw?.values?.['p(95)'])}ms
  ----------------------------------------------------------
  에러율:         ${pct(err)}%   (status != 200)
  느린 응답율:    ${pct(slow)}%   (3초 초과)
  드롭된 반복:    ${droppedCount}건 (${dropPct}%)  <- 0이 아니면 이미 포화
==========================================================
  TSV: ${line}
`;

  return {
    stdout: text,
    [`results/knee_${LABEL}.txt`]: text,
    [`results/knee_${LABEL}.tsv`]: line + '\n',
  };
}

// '2m' / '90s' / '1m30s' 같은 k6 duration 문자열을 초로 바꾼다.
function toSeconds(s) {
  const m = String(s).match(/(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?/);
  if (!m) return 1;
  const h = Number(m[1] || 0);
  const min = Number(m[2] || 0);
  const sec = Number(m[3] || 0);
  const total = h * 3600 + min * 60 + sec;
  return total > 0 ? total : 1;
}
