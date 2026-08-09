-- ============================================================================
-- 키워드 검색 인덱스 미스 — 실행계획 3단 증거
--
-- 목적: "Phase 3에서 만든 인덱스가 한 번도 쓰이지 않았다"는 주장의 재현 가능한 근거.
--       그동안 이 주장은 문서에 서술로만 있었고 캡처된 EXPLAIN 출력이 없었다.
--
-- 사용:
--   docker exec -i sbb-performance-test-db mysql -usbbtest -ptest1234 -t sbb_db \
--     < load-test/explain-index-miss.sql > results/explain_index_miss.txt 2>&1
--
-- 키워드는 '자바'로 고정한다 (FULLTEXT 매칭 1,475건, 결과 집합 검증에 쓰인 것과 동일).
-- ============================================================================

SELECT '===== 데이터 규모 =====' AS `#`;
SELECT
  (SELECT COUNT(*) FROM QUESTION)  AS question_rows,
  (SELECT COUNT(*) FROM ANSWER)    AS answer_rows,
  (SELECT COUNT(*) FROM SITE_USER) AS user_rows;

SELECT '===== 보유 인덱스 =====' AS `#`;
SELECT TABLE_NAME, INDEX_NAME, INDEX_TYPE, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = 'sbb_db' AND TABLE_NAME IN ('QUESTION','ANSWER','SITE_USER')
 GROUP BY TABLE_NAME, INDEX_NAME, INDEX_TYPE
 ORDER BY TABLE_NAME, INDEX_NAME;


-- ────────────────────────────────────────────────────────────────────────────
-- (a) 원본: OR 5항 + LEFT JOIN 4개
--
-- QuestionRepository.findAllByKeyword 의 JPQL을 SQL로 옮긴 것.
-- `:kw = ''` 항은 실제 실행되던 형태 그대로 남겨두었다 (kw가 비어있지 않으면 상수 false).
--
-- 기대 증거: type=ALL, possible_keys=NULL
--   OR의 한 항이 LIKE '%kw%'(앞 와일드카드)라 인덱스를 못 탄다.
--   MySQL은 어차피 전체를 봐야 한다고 판단해 같은 WHERE의 MATCH AGAINST용
--   FULLTEXT 인덱스까지 버린다. 게다가 LEFT JOIN ANSWER가 질문 1만 건을
--   12만 5천 행으로 부풀린다.
-- ────────────────────────────────────────────────────────────────────────────

SELECT '===== (a-1) 원본 OR — 목록 쿼리 =====' AS `#`;
EXPLAIN
SELECT DISTINCT q.* FROM QUESTION q
  LEFT OUTER JOIN SITE_USER u1 ON q.AUTHOR_ID = u1.ID
  LEFT OUTER JOIN ANSWER    a  ON a.QUESTION_ID = q.ID
  LEFT OUTER JOIN SITE_USER u2 ON a.AUTHOR_ID = u2.ID
 WHERE ('자바' = '' OR q.SUBJECT   LIKE '%자바%')
    OR ('자바' = '' OR q.CONTENT   LIKE '%자바%')
    OR ('자바' = '' OR u1.USERNAME LIKE '%자바%')
    OR ('자바' = '' OR a.CONTENT   LIKE '%자바%')
    OR ('자바' = '' OR u2.USERNAME LIKE '%자바%')
 LIMIT 10;

SELECT '===== (a-2) 원본 OR — count 쿼리 (전체 DB 시간의 92%를 먹던 자리) =====' AS `#`;
EXPLAIN
SELECT COUNT(DISTINCT q.ID) FROM QUESTION q
  LEFT OUTER JOIN SITE_USER u1 ON q.AUTHOR_ID = u1.ID
  LEFT OUTER JOIN ANSWER    a  ON a.QUESTION_ID = q.ID
  LEFT OUTER JOIN SITE_USER u2 ON a.AUTHOR_ID = u2.ID
 WHERE ('자바' = '' OR q.SUBJECT   LIKE '%자바%')
    OR ('자바' = '' OR q.CONTENT   LIKE '%자바%')
    OR ('자바' = '' OR u1.USERNAME LIKE '%자바%')
    OR ('자바' = '' OR a.CONTENT   LIKE '%자바%')
    OR ('자바' = '' OR u2.USERNAME LIKE '%자바%');


-- ────────────────────────────────────────────────────────────────────────────
-- (b) 8/8 개선: OR -> UNION 3항 분리, 목록은 WHERE q.ID IN (...)
--
-- 기대 증거:
--   count 쿼리 -> DERIVED + fulltext.  인덱스를 되찾는다.
--   목록 쿼리 -> DEPENDENT SUBQUERY / DEPENDENT UNION.  아직 못 되찾는다.
--     IN (서브쿼리)를 MySQL이 상관 서브쿼리로 바꿔 바깥 q의 매 행마다 재평가하고,
--     MATCH AGAINST가 eq_ref PRIMARY(func)로 떨어진다.
-- ────────────────────────────────────────────────────────────────────────────

SELECT '===== (b-1) UNION + IN — 목록 쿼리 =====' AS `#`;
EXPLAIN
SELECT q.ID FROM QUESTION q WHERE q.ID IN (
    SELECT q2.ID FROM QUESTION q2
     WHERE MATCH(q2.SUBJECT, q2.CONTENT) AGAINST ('자바' IN BOOLEAN MODE)
  UNION
    SELECT q3.ID FROM QUESTION q3 JOIN SITE_USER u1 ON q3.AUTHOR_ID = u1.ID
     WHERE u1.USERNAME LIKE '%자바%'
  UNION
    SELECT a.QUESTION_ID FROM ANSWER a JOIN SITE_USER u2 ON a.AUTHOR_ID = u2.ID
     WHERE u2.USERNAME LIKE '%자바%'
) ORDER BY q.CREATE_DATE DESC LIMIT 10;

SELECT '===== (b-2) UNION — count 쿼리 (a-2 대비. 이 변경은 8/8에 이미 반영) =====' AS `#`;
EXPLAIN
SELECT COUNT(*) FROM (
    SELECT q2.ID FROM QUESTION q2
     WHERE MATCH(q2.SUBJECT, q2.CONTENT) AGAINST ('자바' IN BOOLEAN MODE)
  UNION
    SELECT q3.ID FROM QUESTION q3 JOIN SITE_USER u1 ON q3.AUTHOR_ID = u1.ID
     WHERE u1.USERNAME LIKE '%자바%'
  UNION
    SELECT a.QUESTION_ID FROM ANSWER a JOIN SITE_USER u2 ON a.AUTHOR_ID = u2.ID
     WHERE u2.USERNAME LIKE '%자바%'
) t;


-- ────────────────────────────────────────────────────────────────────────────
-- (c) 8/9 개선: IN (서브쿼리) -> JOIN (파생 테이블)
--
-- 기대 증거: DERIVED (1회 실체화) + fulltext ft_idx_question_search
--            + 파생 테이블이 eq_ref <auto_distinct_key>로 붙는다.
-- ────────────────────────────────────────────────────────────────────────────

SELECT '===== (c-1) UNION + JOIN — 목록 쿼리 =====' AS `#`;
EXPLAIN
SELECT q.ID FROM QUESTION q JOIN (
    SELECT q2.ID AS ID FROM QUESTION q2
     WHERE MATCH(q2.SUBJECT, q2.CONTENT) AGAINST ('자바' IN BOOLEAN MODE)
  UNION
    SELECT q3.ID FROM QUESTION q3 JOIN SITE_USER u1 ON q3.AUTHOR_ID = u1.ID
     WHERE u1.USERNAME LIKE '%자바%'
  UNION
    SELECT a.QUESTION_ID FROM ANSWER a JOIN SITE_USER u2 ON a.AUTHOR_ID = u2.ID
     WHERE u2.USERNAME LIKE '%자바%'
) t ON t.ID = q.ID
 ORDER BY q.CREATE_DATE DESC LIMIT 10;


-- ────────────────────────────────────────────────────────────────────────────
-- 결과 집합 동일성 — 세 형태가 같은 답을 내는지
-- 빨라졌는데 결과가 달라졌다면 최적화가 아니라 버그다.
-- ────────────────────────────────────────────────────────────────────────────

SELECT '===== 결과 집합 동일성 (세 값이 같아야 한다) =====' AS `#`;
SELECT
  (SELECT COUNT(DISTINCT q.ID) FROM QUESTION q
     LEFT OUTER JOIN SITE_USER u1 ON q.AUTHOR_ID = u1.ID
     LEFT OUTER JOIN ANSWER    a  ON a.QUESTION_ID = q.ID
     LEFT OUTER JOIN SITE_USER u2 ON a.AUTHOR_ID = u2.ID
    WHERE q.SUBJECT LIKE '%자바%' OR q.CONTENT LIKE '%자바%'
       OR u1.USERNAME LIKE '%자바%' OR a.CONTENT LIKE '%자바%'
       OR u2.USERNAME LIKE '%자바%') AS `a_원본OR`,
  (SELECT COUNT(*) FROM QUESTION q WHERE q.ID IN (
      SELECT q2.ID FROM QUESTION q2 WHERE MATCH(q2.SUBJECT, q2.CONTENT) AGAINST ('자바' IN BOOLEAN MODE)
      UNION SELECT q3.ID FROM QUESTION q3 JOIN SITE_USER u1 ON q3.AUTHOR_ID = u1.ID WHERE u1.USERNAME LIKE '%자바%'
      UNION SELECT a.QUESTION_ID FROM ANSWER a JOIN SITE_USER u2 ON a.AUTHOR_ID = u2.ID WHERE u2.USERNAME LIKE '%자바%'
   )) AS `b_UNION_IN`,
  (SELECT COUNT(*) FROM QUESTION q JOIN (
      SELECT q2.ID AS ID FROM QUESTION q2 WHERE MATCH(q2.SUBJECT, q2.CONTENT) AGAINST ('자바' IN BOOLEAN MODE)
      UNION SELECT q3.ID FROM QUESTION q3 JOIN SITE_USER u1 ON q3.AUTHOR_ID = u1.ID WHERE u1.USERNAME LIKE '%자바%'
      UNION SELECT a.QUESTION_ID FROM ANSWER a JOIN SITE_USER u2 ON a.AUTHOR_ID = u2.ID WHERE u2.USERNAME LIKE '%자바%'
   ) t ON t.ID = q.ID) AS `c_UNION_JOIN`;
