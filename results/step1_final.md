# STEP 1 최종 — 키워드 검색 병목, 가설 반증부터 threshold 통과까지

작성 2026-08-09. 포트폴리오 2-1절의 "커넥션 풀 포화 여부 검증 중" 문장을 닫기 위한 문서입니다.

---

## 한 줄 결론

**커넥션 풀 가설은 반증됐고, 진짜 병목은 `OR` 조건이 FULLTEXT 인덱스를 무력화시킨 것이었습니다.
쿼리를 세 단계에 걸쳐 재작성해 목표 응답시간 초과율을 51.58% → 0.44%로 낮췄습니다.**

---

## 1. 서사

| 단계 | 내용 | 결과 |
|---|---|---|
| 문제 | 인덱스 튜닝으로 P95 24.04s → 2.18s를 얻었는데 초과율 51.58%가 남음 | threshold 미달 |
| 가설 | HikariCP 커넥션 풀 포화가 원인이다 | — |
| 검증 | 풀 크기를 기본/2배/4배로 바꿔 각각 3분 부하 | 50.73% / 50.00% / 51.75% |
| **반증** | **풀을 4배로 늘려도 초과율이 움직이지 않음** | 가설 기각 |
| 재조사 | 앱 로그와 실행계획 재확인 | 초과의 정체는 SQL 버그 + 인덱스 미사용 |
| 원인 1 | 네이티브 쿼리 `ORDER BY` 중복 → MySQL 1054 | 요청의 50%가 즉시 실패 |
| 원인 2 | `OR` 한 항의 `LIKE '%kw%'`가 같은 WHERE의 FULLTEXT까지 버리게 만듦 | 인덱스 전면 미사용 |
| 해결 | OR → UNION → JOIN 3단 재작성 | **threshold 3개 전부 통과** |

가설이 틀렸다는 사실 자체가 재조사의 출발점이었습니다. 풀이 원인이 아니라는 걸 확인했기 때문에
쿼리 레벨을 다시 들여다봤고, 거기서 인덱스가 한 번도 쓰이지 않고 있었다는 걸 발견했습니다.

---

## 2. 근거 (1) — 메커니즘: 실행계획 3단

원본: `results/explain_index_miss.txt` · 재현: `load-test/explain-index-miss.sql`
데이터 규모 질문 10,000 / 답변 125,692 / 사용자 1,000, kw='자바'(FULLTEXT 매칭 1,475건)

### (a) 원본 — OR 5항 + LEFT JOIN 4개

```sql
SELECT DISTINCT q.* FROM QUESTION q
  LEFT OUTER JOIN SITE_USER u1 ON q.AUTHOR_ID = u1.ID
  LEFT OUTER JOIN ANSWER    a  ON a.QUESTION_ID = q.ID
  LEFT OUTER JOIN SITE_USER u2 ON a.AUTHOR_ID = u2.ID
 WHERE q.SUBJECT LIKE '%자바%' OR q.CONTENT LIKE '%자바%'
    OR u1.USERNAME LIKE '%자바%' OR a.CONTENT LIKE '%자바%' OR u2.USERNAME LIKE '%자바%'
```

```
| id | select_type | table | type   | possible_keys | key  | rows | Extra           |
|  1 | SIMPLE      | q     | ALL    | NULL          | NULL | 9925 | Using temporary |
|  1 | SIMPLE      | u1    | eq_ref | PRIMARY       | ...  |    1 | Distinct        |
|  1 | SIMPLE      | a     | ref    | FKl49sk30...  | ...  |   11 | Distinct        |
|  1 | SIMPLE      | u2    | eq_ref | PRIMARY       | ...  |    1 | Using where     |
```

`type: ALL` · `possible_keys: NULL`. **인덱스가 후보에조차 오르지 못했습니다.**
OR의 한 항이 앞 와일드카드 `LIKE '%kw%'`라 인덱스를 못 타면, MySQL은 어차피 전체를 봐야 한다고
판단해 같은 WHERE에 있는 `MATCH AGAINST`용 FULLTEXT 인덱스까지 버립니다.
거기에 `LEFT JOIN ANSWER`가 질문 1만 건을 12만 5천 행으로 부풀립니다.

→ **Phase 3에서 만든 인덱스 2개가 이때까지 한 번도 사용되지 않았습니다.**

### (b) 1차 재작성 — OR를 UNION 3항으로 분리

각 항이 자기 인덱스를 타도록 조건을 쪼갰습니다. count 쿼리는 인덱스를 되찾았습니다.

```
| id | select_type  | table      | type     | key                    | rows  |
|  1 | PRIMARY      | <derived2> | ALL      | NULL                   | 13916 |
|  2 | DERIVED      | q2         | fulltext | ft_idx_question_search |     1 |   <- 되찾음
```

그런데 **목록 쿼리는 아직입니다.** `WHERE q.ID IN (UNION...)`을 MySQL이 상관 서브쿼리로 바꿉니다.

```
| id | select_type        | table | type   | key     | ref  |
|  2 | DEPENDENT SUBQUERY | q2    | eq_ref | PRIMARY | func |   <- fulltext 아님
|  3 | DEPENDENT UNION    | q3    | eq_ref | PRIMARY | func |
|  4 | DEPENDENT UNION    | a     | ref    | FKl49...| func |
```

`DEPENDENT` = 바깥 q의 **매 행마다** UNION 3항을 전부 재평가한다는 뜻입니다.

### (c) 2차 재작성 — IN 서브쿼리를 JOIN 파생 테이블로

```sql
SELECT q.ID FROM QUESTION q JOIN ( <UNION 3항> ) t ON t.ID = q.ID
 ORDER BY q.CREATE_DATE DESC
```

```
| id | select_type | table      | type     | key                    | Extra       |
|  1 | PRIMARY     | q          | index    | idx_question_covering  | Using index |
|  1 | PRIMARY     | <derived2> | eq_ref   | <auto_distinct_key>    | Using index |
|  2 | DERIVED     | q2         | fulltext | ft_idx_question_search | Ft_hints... |
```

파생 테이블이 **1회만 실체화**되고, `q.ID = t.ID`가 `eq_ref`로 붙습니다. FULLTEXT 접근 확보.

### 요약

| | q 접근 | possible_keys | MATCH AGAINST |
|---|---|---|---|
| (a) 원본 OR | **`ALL`** (9,925행) | **`NULL`** | 후보 배제 |
| (b) UNION + IN | `index` | NULL | `DEPENDENT SUBQUERY` → `eq_ref PRIMARY(func)` |
| (c) UNION + JOIN | `index` | `PRIMARY` | **`fulltext ft_idx_question_search`** |

---

## 3. 근거 (2) — 크기: DB 단독 실측

원본: `results/kw_join_bench.md` · 재현: `bash load-test/bench-kw-join.sh 7`
앱을 모두 내리고 `SET profiling=1` + `SHOW PROFILES`로 statement별 duration을 받았습니다.
7회 반복 중 최소값. A = IN 목록쿼리, B = JOIN 목록쿼리, C = countQuery(공통).

| kw | FT매칭 | A+C | B+C | Δ |
|---|---|---|---|---|
| 자바 | 1,475 | 11.09 | 12.76 | +15% |
| 스프링 | 1,152 | 24.71 | 25.31 | +2% |
| JPA | 891 | 29.85 | 25.02 | −16% |
| 쿼리 | 643 | 21.09 | 8.88 | −58% |
| 오류 | 508 | 15.33 | 7.85 | −49% |
| 성능 | 333 | 18.77 | 5.46 | −71% |
| 테스트 | 100 | 63.98 | 5.57 | **−91%** |
| 질문 | 0 | 506.97 | 6.72 | **−99%** |

**IN 형태의 비용은 매칭 건수에 반비례합니다.** `LIMIT 10`을 채울 때까지 q를 훑는데 매 행마다
UNION을 재평가하므로, 흔하지만 본문에 없는 검색어(`질문`)가 최악입니다. 507ms가 한 건에 듭니다.

트래픽 가중 평균 **21.78ms → 14.89ms (−32%)**, 키워드 간 편차 **13배 → 4배**.
평균보다 편차 축소가 본질입니다 — P95를 만드는 건 최악 키워드이기 때문입니다.

정직한 약점: `user1`처럼 작성자명이 111명 매칭되는 키워드에서는 파생 테이블 실체화 비용이 드러나
IN보다 2배 느립니다(36.7 → 70.0ms). 다만 그 조건에선 countQuery가 이미 35ms를 쓰므로
UNION 구조 자체의 비용이지 이번 변경이 만든 비용이 아닙니다.

---

## 4. 근거 (3) — 정확성: 결과가 변하지 않았음

빨라졌는데 결과가 달라졌다면 최적화가 아니라 버그입니다.

| 검증 | 방법 | 결과 |
|---|---|---|
| 결과 집합 | 세 쿼리 형태의 매칭 건수 비교 (kw='자바') | **1475 / 1475 / 1475** |
| HTTP 응답 | 빈 검색 3종 + 키워드 7종 + 경계 3종 + 페이지 경계 2종 md5 | **15케이스 전부 일치** |
| 회귀 테스트 | IN↔JOIN 동치성 (키워드 5종 × 페이지 0·1), 쿼리 수 고정, 페이지네이션 보존 | **8건 통과** |

원본: `results/kw_identity_check.txt`, `src/test/.../QuestionSearchRegressionTest.java`

---

## 5. 최종 확인 — k6 threshold 통과 여부

`load-test/scenario-a.js`, 100 VU / 3분 4스테이지. 회차마다 앱 완전 재기동 + 20초 사전 워밍업.
재현: `bash load-test/run-final-a.sh <회차>`

### 측정 환경 변경 이력 (숨기지 않고 기록)

측정 도중 Windows Defender 실시간 검사 제외 폴더를 추가했습니다. 제외 적용 전후는
**같은 표에 섞지 않습니다.** 아래 최종 표는 제외 적용 **이후** 3회만으로 구성했습니다.

### 최종 (Defender 제외 적용 후)

| 빌드 | 회차 | TPS | P50 | P95 | P99 | 에러율 | 느린 응답율 | threshold |
|---|---|---|---|---|---|---|---|---|
| **JOIN** | 6 | 39.3 | 788ms | 2058ms | 2706ms | 0.00% | 0.34% | **통과** |
| **JOIN** | 7 | 40.5 | 736ms | **2014ms** | 2681ms | 0.00% | **0.44%** | **통과** |
| **JOIN** | 8 | 37.6 | 872ms | 2177ms | 2776ms | 0.00% | 0.40% | **통과** |
| 이전(대조) | old_2 | 36.2 | 927ms | 2220ms | 2992ms | 0.00% | 0.98% | 통과 |
| 이전(대조) | old_3 | 33.3 | 997ms | 2732ms | 3735ms | 0.00% | 3.15% | 통과 |

3회가 P95 2014~2177ms, 느린 응답율 0.34~0.44%로 좁게 모입니다.
대조군(JOIN 전 빌드)을 같은 환경에서 2회 재서 **회귀가 없음**도 함께 확인했습니다.

### 제외 적용 전 기록 (참고 보존, 최종 표에서 제외)

| 빌드 | 회차 | TPS | P95 | 느린 응답율 | threshold |
|---|---|---|---|---|---|
| JOIN | 1 | 27.1 | 3046ms | 5.36% | 미달 |
| JOIN | 2 | 33.7 | 2495ms | 1.83% | 통과 |
| JOIN | 3 | 29.6 | 3303ms | 8.29% | 미달 |
| JOIN | 4 | 27.9 | 3359ms | 6.67% | 미달 |
| JOIN | 5 | 36.1 | 2424ms | 1.51% | 통과 |
| 이전 | old_1 | 30.6 | 2679ms | 2.41% | 통과 |

TPS가 27~36으로 흔들리고 그에 따라 초과율이 1.51~8.29%로 따라 움직입니다.
Defender 실시간 검사가 JVM 클래스 로딩과 로그 파일 쓰기를 훑던 구간으로 보입니다
(앱 기동이 평소 21~29초에서 68초까지 늘어난 적이 있습니다).

---

## 6. 앱 레벨 A/B는 근거에서 제외했습니다

두 빌드를 8080/8081에 동시 기동해 한 반복에서 둘 다 때리는 **동시 교차 쌍 측정**도 시도했습니다.
`perf가 빠른 쌍의 비율`을 지표로 삼았고 혼합 분포에서 52.7~59.2%(널 대조 49.8%)가 나왔습니다.
그러나 **같은 jar를 양쪽 포트에 띄운 대칭 대조군이 포화 구간에서 44.4 / 50.4 / 84.4 / 97.1%로
널뛰었습니다.** 동일 코드가 이 정도 편차를 낸다는 건 승패 쌍이 서로 독립이 아니라는 직접 증거이고,
그러면 표본 수 기반 표준오차 계산이 성립하지 않습니다. 도착률을 절반으로 낮추면 48.3%로 정상화되는
것으로 보아 구조적 편향이 아니라 포화 상태에서 런 단위로 고착되는 큐잉 불안정입니다.
근거로 쓸 수 없다고 판단해 **스스로 걷어냈습니다.**
원본은 `results/ab_*.txt`에 그대로 보존했습니다.

5절의 scenario-a 측정은 이 문제에서 자유롭습니다 — 두 빌드의 쌍별 승패를 비교하는 게 아니라
단일 빌드를 반복해 "threshold를 통과하는가"만 보기 때문입니다.

---

## 7. 전체 추이

| 시점 | 변경 | TPS | P95 | 목표 응답시간 초과율(3s) |
|---|---|---|---|---|
| 튜닝 전 | — | 4.42 | 24,040ms | 79.88% |
| 인덱스 도입 | 커버링 + FULLTEXT(ngram) | 37.3 | 2,180ms | 51.58% |
| 버그 수정 + UNION | ORDER BY 중복 제거, OR → UNION | 25.9 | 2,727ms | 1.54% |
| N+1 제거 | 세미 조인 3단계 | 40.3 | 2,597ms | 2.30% |
| **JOIN 전환** | **IN 서브쿼리 → 파생 테이블** | **40.5** | **2,014ms** | **0.44%** |

### 지표 표기에 대한 주석 (반드시 병기할 것)

2026-08-09에 k6 커스텀 지표를 셋으로 분리했습니다. 그 전에는 하나가 두 가지를 뭉개고 있었습니다.

| 지표 | 정의 | threshold |
|---|---|---|
| `scenario_a_error_rate` | `status != 200` (서버 실패)만 | `rate<0.05` |
| `scenario_a_slow_rate` | 3초 초과 (SLO 위반)만 | `rate<0.05` |
| `scenario_a_search_duration` | 검색 지연 | `p(95)<3000` |

79.88% / 51.58% / 1.54%는 **구 지표**(서버 실패 OR 3초 초과)입니다.
현재는 **에러율이 0.00%**이므로 구 지표로 환산해도 값은 `0.00% + 0.44% = 0.44%`로 같습니다.
즉 위 표의 마지막 칸은 이전 값들과 직접 비교 가능합니다. 지표를 바꿔 좋아 보이게 만든 것이 아닙니다.

---

## 8. 포트폴리오 2-1절 교체 문안

**현재 문장 (삭제 대상)**

> 그러나 목표 응답시간(3s) 초과율은 79.88% → 51.58%로 개선되었을 뿐 threshold('rate<0.05')를
> 여전히 통과하지 못했습니다. 이는 쿼리 자체가 아니라 DB 커넥션 풀 포화 등 인프라 레벨 병목일
> 가능성을 시사했고, 현재 이를 검증하는 부하 테스트를 추가로 설계해 진행하고 있습니다.

**교체 문안**

> 그러나 목표 응답시간(3s) 초과율은 51.58%로 남아 threshold('rate<0.05')를 통과하지 못했습니다.
> DB 커넥션 풀 포화를 가설로 세우고 풀 크기를 기본/2배/4배로 바꿔 각각 측정했으나
> 초과율은 50.73% / 50.00% / 51.75%로 움직이지 않았습니다. **가설을 반증한 것입니다.**
>
> 원인을 다시 조사한 결과 병목은 인프라가 아니라 쿼리에 있었습니다. `EXPLAIN` 결과
> `type: ALL`, `possible_keys: NULL` — Phase 3에서 도입한 인덱스 2개가 한 번도 사용되지
> 않고 있었습니다. `OR`로 묶인 조건 중 하나가 앞 와일드카드 `LIKE '%kw%'`라 인덱스를 못 타자,
> MySQL이 같은 WHERE의 `MATCH AGAINST`용 FULLTEXT 인덱스까지 버리고 풀스캔한 것입니다.
>
> 조건을 `UNION` 3항으로 분리해 각 항이 자기 인덱스를 타게 하고, 목록 쿼리의 `IN` 서브쿼리를
> 파생 테이블 `JOIN`으로 바꿔 상관 서브쿼리 재평가를 제거했습니다. `EXPLAIN`에서
> `fulltext ft_idx_question_search` 접근을 확인했고, 재작성 전후 결과 집합이 동일함을
> HTTP 응답 15케이스 md5 비교와 회귀 테스트 8건으로 고정했습니다.
>
> 그 결과 **목표 응답시간 초과율 51.58% → 0.44%, HTTP 실패율 0.00%로 k6 threshold 3개를
> 모두 통과**했습니다(100 VU / 3분, 3회 반복 시 0.34~0.44%).

**표 갱신**

| 성능 지표 | 시나리오 A (기준 3s) | 시나리오 B (기준 500ms) |
|---|---|---|
| 목표 응답시간 초과율 | 79.88% → 51.58% → **0.44%** | 0.00% |
| HTTP 실패율 | 2.27% → **0.00%** | 0.00% |
| TPS | 4.42 → **40.5 req/s** | — |

---

## 9. 남은 것 (이번 범위 밖)

- **countQuery가 다음 병목입니다.** JOIN 전환 후 목록 쿼리 비용이 count와 거의 같아졌습니다
  (`스프링` 12.36 / 12.95, `JPA` 12.88 / 12.14). 같은 파생 테이블을 두 번 실체화하는 구조입니다.
  UNION의 중복 제거가 전체 실체화를 요구하므로 쿼리 재작성만으로는 어렵고,
  카운트 캐싱·근사 카운트·무한 스크롤 전환 같은 구조 변경이 필요합니다.
- `SITE_USER.USERNAME`은 BTREE 유니크뿐이라 `LIKE '%kw%'`를 여전히 못 탑니다.
  UNION 2·3항에 구조적으로 남아 있습니다.
- 무릎 탐색(`scenario-knee.js`)은 범위 밖으로 중단했습니다. 포화점 25~28 req/s는
  `results/knee_summary.md`에 기록돼 있습니다.
