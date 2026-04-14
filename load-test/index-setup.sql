-- ============================================================
-- Phase 3 시나리오 A: 커버링 인덱스 설계
-- 대상 DB: sbb_db (MySQL 8.0)
-- ============================================================
-- 실행 순서:
--   1. EXPLAIN 분석 (Before) — 현재 실행 계획 확인
--   2. 인덱스 생성
--   3. EXPLAIN 분석 (After) — 개선된 실행 계획 확인


-- ============================================================
-- STEP 1: BEFORE — 인덱스 없는 상태의 실행 계획
-- ============================================================

-- [1-A] 전체 목록 조회 (kw = '' 케이스)
-- 예상: type=ALL (full scan) + Using filesort
EXPLAIN SELECT DISTINCT q.*
FROM QUESTION q
ORDER BY q.CREATE_DATE DESC
LIMIT 10 OFFSET 0;

-- [1-B] 키워드 검색 (kw = '스프링' 케이스)
-- 예상: type=ALL on 4 tables, Using temporary + Using filesort
--       → 10K 질문 × ~10 답변 = 100K행 스캔 후 DISTINCT 제거
EXPLAIN SELECT DISTINCT q.*
FROM QUESTION q
LEFT JOIN SITE_USER u1 ON q.AUTHOR_ID = u1.ID
LEFT JOIN ANSWER a     ON a.QUESTION_ID = q.ID
LEFT JOIN SITE_USER u2 ON a.AUTHOR_ID = u2.ID
WHERE q.SUBJECT     LIKE '%스프링%'
   OR q.CONTENT     LIKE '%스프링%'
   OR u1.USERNAME   LIKE '%스프링%'
   OR a.CONTENT     LIKE '%스프링%'
   OR u2.USERNAME   LIKE '%스프링%'
ORDER BY q.CREATE_DATE DESC
LIMIT 10 OFFSET 0;


-- ============================================================
-- STEP 2: 인덱스 생성
-- ============================================================

-- [INDEX 1] 페이지네이션 정렬용 커버링 인덱스
-- 목적: ORDER BY CREATE_DATE DESC + LIMIT 에서 filesort 제거
-- 포함 컬럼: ID, SUBJECT, AUTHOR_ID → 목록 뷰에서 추가 row lookup 없이 인덱스만으로 응답
CREATE INDEX idx_question_covering
    ON QUESTION (CREATE_DATE DESC, ID, SUBJECT, AUTHOR_ID);


-- [INDEX 2] FULLTEXT 검색 인덱스
-- 목적: LIKE %kw% (앞 와일드카드) 대신 MATCH AGAINST 사용 → 인덱스 활성화
-- 대상: 검색 빈도가 높은 SUBJECT + CONTENT
CREATE FULLTEXT INDEX ft_idx_question_search
    ON QUESTION (SUBJECT, CONTENT)
    WITH PARSER ngram;  -- 한글 검색을 위한 ngram 파서


-- [INDEX 3] 답변 작성자 검색용 (kw가 답변 내용에 있는 경우)
-- Answer 테이블의 QUESTION_ID는 FK 인덱스가 이미 있으므로 추가 불필요
-- CONTENT 컬럼 FULLTEXT 추가
CREATE FULLTEXT INDEX ft_idx_answer_content
    ON ANSWER (CONTENT)
    WITH PARSER ngram;


-- ============================================================
-- STEP 3: AFTER — 인덱스 적용 후 실행 계획
-- ============================================================

-- [3-A] 전체 목록 조회 — 커버링 인덱스 적용
-- 예상: type=index (index scan) + Using index → filesort 제거
EXPLAIN SELECT DISTINCT q.*
FROM QUESTION q
ORDER BY q.CREATE_DATE DESC
LIMIT 10 OFFSET 0;


-- [3-B] 키워드 검색 — FULLTEXT 인덱스 적용 (쿼리 변경 필요)
-- 예상: type=fulltext → table scan 대비 수십 배 빠름
EXPLAIN SELECT DISTINCT q.*
FROM QUESTION q
LEFT JOIN SITE_USER u1 ON q.AUTHOR_ID = u1.ID
LEFT JOIN ANSWER a     ON a.QUESTION_ID = q.ID
         AND MATCH(a.CONTENT) AGAINST ('스프링' IN BOOLEAN MODE)
LEFT JOIN SITE_USER u2 ON a.AUTHOR_ID = u2.ID
WHERE MATCH(q.SUBJECT, q.CONTENT) AGAINST ('스프링' IN BOOLEAN MODE)
   OR u1.USERNAME LIKE '%스프링%'
   OR u2.USERNAME LIKE '%스프링%'
ORDER BY q.CREATE_DATE DESC
LIMIT 10 OFFSET 0;


-- ============================================================
-- STEP 4: 인덱스 사용 통계 확인 (테스트 후)
-- ============================================================
SELECT
    TABLE_NAME,
    INDEX_NAME,
    SEQ_IN_INDEX,
    COLUMN_NAME,
    CARDINALITY
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'sbb_db'
  AND TABLE_NAME IN ('QUESTION', 'ANSWER')
ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX;
