/**
 * baseline vs perf 동시 교차 A/B — 쌍(pair) 측정
 *
 * 왜 이 설계인가:
 *   2026-08-09 무릎 탐색에서 이 호스트는 수십 초~2분짜리 간섭 에피소드를 일으킨다는 것을
 *   확인했다. 동일 코드·동일 도착률 4회에서 P95가 5862 / 5166 / 3502 / 633 ms로 나왔고,
 *   간섭이 2분 런 전체를 덮을 수도 있었다. 따라서 "baseline 런"과 "perf 런"을 따로 재서
 *   비교하면 두 런이 서로 다른 간섭을 맞아 결과가 통째로 뒤집힐 수 있다.
 *
 *   그래서 두 빌드를 동시에 띄우고(8080 / 8081) 한 반복 안에서 둘 다 때린다.
 *   간섭은 두 빌드에 똑같이 걸리므로 차이값에서 상쇄된다.
 *
 *   상쇄를 위해 세 가지를 통제한다:
 *   1. 쿼리 동일 - 한 반복의 두 요청은 완전히 같은 URL을 쓴다.
 *      (키워드 검색과 빈 검색은 비용이 자릿수로 다르므로, 구성이 어긋나면 그 자체가 잡음이다)
 *   2. 순서 교대 - 반복마다 먼저 때리는 쪽을 뒤집는다.
 *      뒤에 오는 요청은 앞 요청이 데워놓은 MySQL 버퍼 풀 덕을 본다. 고정하면 편향이 된다.
 *   3. 쿼리 시퀀스 고정 - 초기화 시 고정 시드로 섞은 배열을 반복 인덱스로 참조한다.
 *      실행마다 쿼리 순서가 달라지지 않으므로 런 간 비교도 가능하다.
 *
 * 사전 조건:
 *   8080 = baseline (results/sbb-baseline.jar)
 *   8081 = perf     (results/sbb-perf.jar, --server.port=8081)
 *
 * 실행:
 *   RATE=10 k6 run load-test/scenario-ab.js
 *   -> 초당 10쌍 = 총 20 req/s. 무릎(25~28 req/s)의 약 0.7배 지점.
 *
 * 환경변수:
 *   RATE      초당 쌍 수. 기본 10 (총 부하는 이 값의 2배)
 *   DURATION  측정 구간. 기본 '2m'
 *   LABEL     결과 파일 라벨. 기본 'ab'
 *   A_URL     기본 http://localhost:8080  (baseline)
 *   B_URL     기본 http://localhost:8081  (perf)
 */

import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const RATE = Number(__ENV.RATE || 10);
const DURATION = __ENV.DURATION || '2m';
const LABEL = __ENV.LABEL || 'ab';
const A_URL = __ENV.A_URL || 'http://localhost:8080';
const B_URL = __ENV.B_URL || 'http://localhost:8081';

const durA = new Trend('ab_baseline', true);
const durB = new Trend('ab_perf', true);
// 쌍별 차이 (perf - baseline). 음수면 perf가 빠르다.
const diff = new Trend('ab_diff', true);
const perfFaster = new Rate('ab_perf_faster');
// 순서 효과 검증용: 먼저/나중에 맞은 요청의 지연.
// 둘이 크게 다르면 캐시 순서 효과가 실재한다는 뜻이고, 교대가 그걸 상쇄하고 있다는 근거가 된다.
const durFirst = new Trend('ab_first', true);
const durSecond = new Trend('ab_second', true);
const pairs = new Counter('ab_pairs');
const errA = new Rate('ab_baseline_error');
const errB = new Rate('ab_perf_error');

export const options = {
  scenarios: {
    warmup: {
      executor: 'constant-arrival-rate',
      exec: 'warmup',
      rate: 3,
      timeUnit: '1s',
      duration: '20s',
      preAllocatedVUs: 10,
      maxVUs: 20,
      gracefulStop: '5s',
    },
    measure: {
      executor: 'constant-arrival-rate',
      exec: 'measure',
      startTime: '25s',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      // 한 반복이 요청 2건을 순차 처리하므로 반복 시간이 지연의 2배다.
      preAllocatedVUs: Math.max(50, RATE * 10),
      maxVUs: Math.max(50, RATE * 10),
      gracefulStop: '15s',
    },
  },
  summaryTrendStats: ['min', 'med', 'avg', 'p(95)', 'p(99)', 'max'],
  thresholds: {},
};

// scenario-a.js / scenario-knee.js와 동일한 트래픽 분포.
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

// KW_ONLY=1 이면 빈 검색(kw='')을 제외하고 키워드 검색만 쏜다.
//
// 왜 필요한가: 기본 분포는 절반(weight 50/100)이 빈 검색이다. 빈 검색은
// findAllQuestionIds 경로라 키워드 쿼리를 바꿔도 두 빌드가 완전히 같은 일을 한다.
// 그 쌍들은 동전 던지기가 되어 승률을 50%로 끌어내린다. 즉 기본 분포의 승률은
// "개선이 없다"가 아니라 "개선 대상이 트래픽의 절반"을 뜻한다.
// 변경 자체가 유효한지 보려면 대상 트래픽만 떼어내서 봐야 한다.
//
// 최종 판단은 기본 분포(= 실제 트래픽) 쪽 숫자로 한다. KW_ONLY는 진단용이다.
const KW_ONLY = __ENV.KW_ONLY === '1';

// 가중치를 그대로 펼친 100칸 배열을 만들고, 고정 시드로 한 번만 섞는다.
// Math.random()을 쓰지 않으므로 실행마다 같은 쿼리 순서가 재현된다.
const SEQUENCE = buildSequence();

function buildSequence() {
  const arr = [];
  for (const s of SEARCH_SCENARIOS) {
    if (KW_ONLY && s.kw === '') continue;
    for (let i = 0; i < s.weight; i++) arr.push(s);
  }
  // 선형 합동 생성기(LCG)로 결정론적 Fisher-Yates 셔플
  let seed = 20260809;
  const next = () => {
    seed = (seed * 1103515245 + 12345) % 2147483648;
    return seed / 2147483648;
  };
  for (let i = arr.length - 1; i > 0; i--) {
    const j = Math.floor(next() * (i + 1));
    const t = arr[i];
    arr[i] = arr[j];
    arr[j] = t;
  }
  return arr;
}

function urlFor(base, s) {
  return `${base}/question/list?page=${s.page}&kw=${encodeURIComponent(s.kw)}`;
}

export function warmup() {
  const s = SEQUENCE[exec.scenario.iterationInTest % SEQUENCE.length];
  http.get(urlFor(A_URL, s), { tags: { phase: 'warmup' } });
  http.get(urlFor(B_URL, s), { tags: { phase: 'warmup' } });
}

export function measure() {
  const i = exec.scenario.iterationInTest;
  const s = SEQUENCE[i % SEQUENCE.length];
  const baselineFirst = i % 2 === 0;

  const tags = {
    phase: 'measure',
    kw_type: s.kw === '' ? 'full_scan' : 'keyword_search',
  };

  let resA;
  let resB;
  if (baselineFirst) {
    resA = http.get(urlFor(A_URL, s), { tags: { ...tags, build: 'baseline', slot: 'first' } });
    resB = http.get(urlFor(B_URL, s), { tags: { ...tags, build: 'perf', slot: 'second' } });
    durFirst.add(resA.timings.duration);
    durSecond.add(resB.timings.duration);
  } else {
    resB = http.get(urlFor(B_URL, s), { tags: { ...tags, build: 'perf', slot: 'first' } });
    resA = http.get(urlFor(A_URL, s), { tags: { ...tags, build: 'baseline', slot: 'second' } });
    durFirst.add(resB.timings.duration);
    durSecond.add(resA.timings.duration);
  }

  check(resA, { 'baseline 200': (r) => r.status === 200 });
  check(resB, { 'perf 200': (r) => r.status === 200 });

  const a = resA.timings.duration;
  const b = resB.timings.duration;

  durA.add(a);
  durB.add(b);
  errA.add(resA.status !== 200);
  errB.add(resB.status !== 200);

  // 두 요청이 모두 성공한 쌍만 차이 계산에 넣는다.
  if (resA.status === 200 && resB.status === 200) {
    diff.add(b - a);
    perfFaster.add(b < a);
    pairs.add(1);
  }
}

export function handleSummary(data) {
  const A = data.metrics.ab_baseline;
  const B = data.metrics.ab_perf;
  const D = data.metrics.ab_diff;
  const F = data.metrics.ab_first;
  const S = data.metrics.ab_second;
  const win = data.metrics.ab_perf_faster;
  const n = data.metrics.ab_pairs?.values?.count ?? 0;

  const v = (m, k) => (m?.values?.[k] != null ? m.values[k].toFixed(0) : 'N/A');
  const sgn = (m, k) => {
    const x = m?.values?.[k];
    if (x == null) return 'N/A';
    return (x > 0 ? '+' : '') + x.toFixed(0);
  };
  const pct = (m) => (m ? (m.values.rate * 100).toFixed(1) : 'N/A');

  const aMed = A?.values?.med;
  const bMed = B?.values?.med;
  const rel = aMed > 0 && bMed != null ? (((bMed - aMed) / aMed) * 100).toFixed(1) : 'N/A';

  const line = [
    LABEL, RATE, n,
    v(A, 'med'), v(A, 'p(95)'), v(A, 'p(99)'),
    v(B, 'med'), v(B, 'p(95)'), v(B, 'p(99)'),
    sgn(D, 'med'), rel, pct(win),
    v(F, 'med'), v(S, 'med'),
  ].join('\t');

  const text = `
==========================================================
  동시 교차 A/B — ${LABEL}
  baseline ${A_URL}  vs  perf ${B_URL}
==========================================================
  도착률:        ${RATE} 쌍/s  (총 ${RATE * 2} req/s), 측정 ${DURATION}
  유효 쌍 수:    ${n}건
  ----------------------------------------------------------
                   P50        P95        P99
  baseline      ${v(A, 'med').padStart(6)}ms  ${v(A, 'p(95)').padStart(6)}ms  ${v(A, 'p(99)').padStart(6)}ms
  perf          ${v(B, 'med').padStart(6)}ms  ${v(B, 'p(95)').padStart(6)}ms  ${v(B, 'p(99)').padStart(6)}ms
  ----------------------------------------------------------
  쌍별 차이 (perf - baseline), 음수면 perf가 빠름
    P50:         ${sgn(D, 'med')}ms
    P95:         ${sgn(D, 'p(95)')}ms
    최소/최대:   ${sgn(D, 'min')}ms / ${sgn(D, 'max')}ms
  P50 기준 상대 변화: ${rel}%
  perf가 빠른 쌍 비율: ${pct(win)}%   (50%면 차이 없음)
  ----------------------------------------------------------
  순서 효과 점검 (교대로 상쇄되고 있는지)
    먼저 맞은 쪽 P50:   ${v(F, 'med')}ms
    나중에 맞은 쪽 P50: ${v(S, 'med')}ms
  ----------------------------------------------------------
  에러율  baseline ${pct(data.metrics.ab_baseline_error)}%   perf ${pct(data.metrics.ab_perf_error)}%
==========================================================
  TSV: ${line}
`;

  return {
    stdout: text,
    [`results/ab_${LABEL}.txt`]: text,
    [`results/ab_${LABEL}.tsv`]: line + '\n',
  };
}
