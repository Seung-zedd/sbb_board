package com.mysite.sbb.question;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface QuestionRepository extends JpaRepository<Question, Long> {
    // findById만 기본적으로 제공해주므로 새로 작성
//    Question findBySubject(String subject);
    Question findBySubjectAndContent(String subject, String content);

    List<Question> findBySubjectLike(String subject);

    @Override
    Page<Question> findAll(Pageable pageable);

    // ── 기존 쿼리 (LIKE %kw% — 앞 와일드카드로 인덱스 무효화, Cartesian product 발생) ──
    // Phase 3 시나리오 A EXPLAIN 분석 대상: 인덱스 적용 전 baseline으로 사용
    @Query("select distinct q "
            + "from Question q "
            + "left outer join SiteUser u1 on q.author=u1 "
            + "left outer join Answer a on a.question=q "
            + "left outer join SiteUser u2 on a.author=u2 "
            + "where (:kw = '' OR q.subject like %:kw%) "
            + "or (:kw = '' OR q.content like %:kw%) "
            + "or (:kw = '' OR u1.username like %:kw%) "
            + "or (:kw = '' OR a.content like %:kw%) "
            + "or (:kw = '' OR u2.username like %:kw%)")
    Page<Question> findAllByKeyword(@Param("kw") String kw, Pageable pageable);

    // ── 개선 쿼리 (FULLTEXT 인덱스 활용 — index-setup.sql 실행 후 사용 가능) ──
    // MATCH AGAINST: ft_idx_question_search(SUBJECT, CONTENT)
    // 한글 지원: ngram 파서 적용 필요 (index-setup.sql 참고)
    //
    // 검색 조건 3개를 OR가 아니라 UNION으로 합친다.
    //
    // OR로 묶으면 MySQL이 FULLTEXT 인덱스를 통째로 버린다. OR의 다른 항이
    // LIKE %kw% (앞 와일드카드)라 인덱스를 못 타므로, 어차피 전체를 봐야 한다고
    // 판단해 MATCH AGAINST까지 풀스캔으로 처리하기 때문이다. 게다가
    // LEFT JOIN ANSWER가 질문 1만 건을 12만 5천 행으로 부풀린다.
    // 그 결과 countQuery 한 건이 27만 행을 검사하며 평균 8초가 걸렸다
    // (2026-08-08 부하 테스트 전체 DB 시간의 92%. results/summary_metrics_fix.txt).
    //
    // UNION으로 분리하면 각 항이 자기 인덱스를 탄다:
    //   1항 ft_idx_question_search(FULLTEXT), 2·3항 SITE_USER 인덱스 스캔 후 조인.
    // 결과 집합은 OR 버전과 동일하며(중복은 UNION이 제거하므로 DISTINCT 불필요),
    // 실측 countQuery 792ms -> 6.98ms, 목록 쿼리 256ms -> 5.24ms.
    //
    // 주의: 아래 네이티브 쿼리는 ORDER BY를 자체 포함한다. Pageable에 Sort를 실어
    // 넘기면 프로퍼티명이 컬럼명으로 변환되지 않은 채 append되어 MySQL 1054가 난다.
    // QuestionService.getList가 Sort 없는 PageRequest를 넘기는 이유다.
    @Query(value = "SELECT q.* FROM QUESTION q WHERE q.ID IN ("
            + "  SELECT q2.ID FROM QUESTION q2 "
            + "   WHERE MATCH(q2.SUBJECT, q2.CONTENT) AGAINST (:kw IN BOOLEAN MODE) "
            + "  UNION "
            + "  SELECT q3.ID FROM QUESTION q3 JOIN SITE_USER u1 ON q3.AUTHOR_ID = u1.ID "
            + "   WHERE u1.USERNAME LIKE %:kw% "
            + "  UNION "
            + "  SELECT a.QUESTION_ID FROM ANSWER a JOIN SITE_USER u2 ON a.AUTHOR_ID = u2.ID "
            + "   WHERE u2.USERNAME LIKE %:kw% "
            + ") ORDER BY q.CREATE_DATE DESC",
            countQuery = "SELECT COUNT(*) FROM ("
            + "  SELECT q2.ID FROM QUESTION q2 "
            + "   WHERE MATCH(q2.SUBJECT, q2.CONTENT) AGAINST (:kw IN BOOLEAN MODE) "
            + "  UNION "
            + "  SELECT q3.ID FROM QUESTION q3 JOIN SITE_USER u1 ON q3.AUTHOR_ID = u1.ID "
            + "   WHERE u1.USERNAME LIKE %:kw% "
            + "  UNION "
            + "  SELECT a.QUESTION_ID FROM ANSWER a JOIN SITE_USER u2 ON a.AUTHOR_ID = u2.ID "
            + "   WHERE u2.USERNAME LIKE %:kw% "
            + ") t",
            nativeQuery = true)
    Page<Question> findAllByKeywordWithFulltext(@Param("kw") String kw, Pageable pageable);

    // ────────────────────────────────────────────────────────────────────
    // N+1 제거를 위한 세미 조인 3단계 쿼리
    //
    // 기존에는 Page<Question>을 그대로 받아 QuestionListItemDto.from에 넘겼는데,
    // from이 getAuthor()와 getAnswerList().size()를 건드리면서 페이지당
    // 1 + 10(author) + 10(answerList) = 최대 21쿼리가 나갔다.
    // 2026-08-08 부하 테스트에서 답변 조회 62,796회(131초) + 작성자 조회 52,519회(101초)로
    // 전체 DB 시간의 34%를 차지했다 (results/summary_metrics_union.txt).
    //
    // STEP 1에서 id만 페이징으로 뽑고, STEP 2에서 IN 절 + JOIN FETCH로 한 번에 가져오고,
    // STEP 3(AnswerRepository)에서 답변 수를 GROUP BY로 집계한다.
    // 페이지 크기와 무관하게 쿼리 3회로 고정된다.
    // ────────────────────────────────────────────────────────────────────

    /**
     * STEP 1 (검색어 없음): 질문 ID만 페이징 조회.
     * JPQL이므로 Pageable에 Sort(createDate)를 실어도 컬럼명으로 정상 변환된다.
     */
    @Query("select q.id from Question q")
    Page<Long> findAllQuestionIds(Pageable pageable);

    /**
     * STEP 1 (검색어 있음): UNION 결과를 파생 테이블로 만들어 QUESTION과 JOIN한다.
     *
     * ── IN (서브쿼리)에서 JOIN (파생 테이블)로 바꾼 이유 ──
     *
     * WHERE q.ID IN (...) 형태에서 MySQL은 UNION을 상관 서브쿼리로 변환한다.
     * EXPLAIN이 DEPENDENT SUBQUERY / DEPENDENT UNION을 찍고, 바깥 q의 매 행마다
     * UNION 세 항을 전부 다시 평가한다. MATCH AGAINST도 FULLTEXT 접근이 아니라
     * PRIMARY eq_ref(func)로 떨어져 인덱스가 무의미해진다.
     *
     * 그래서 비용이 매칭 건수에 반비례한다. ORDER BY CREATE_DATE DESC LIMIT 10이
     * 10건을 채울 때까지 q를 훑는데, 희귀 키워드일수록 훑는 행이 늘기 때문이다.
     * 실측(2026-08-09, results/kw_join_bench.md):
     *   자바(1475건) 4.8ms / 테스트(100건) 61.6ms / 0건 470.5ms
     * 즉 사용자가 실제로 칠 법한 드문 검색어가 최악 케이스다.
     *
     * JOIN으로 바꾸면 파생 테이블이 한 번만 실체화되고 q.ID = t.ID는
     * eq_ref(auto_distinct_key)로 붙는다. MATCH AGAINST도 fulltext 접근을 되찾는다.
     * 비용이 매칭 건수에 비례하게 바뀌어 위 3.0~12.9ms 범위로 수렴한다.
     * 시나리오 가중 평균(목록+count) 21.78ms -> 14.89ms (-32%).
     *
     * 트레이드오프: 매칭이 조밀한 키워드는 IN 쪽의 조기 종료가 유리해 소폭 손해다
     * (자바 4.8 -> 6.5ms). 대신 최악 케이스 470ms가 사라진다. 편차 13배 -> 4배.
     *
     * UNION이 중복을 제거하므로 t.ID는 유일하고, 결과 집합은 IN 버전과 동일하다
     * (QuestionSearchRegressionTest.findQuestionIds_matchesLegacyEntityQuery가 고정한다).
     *
     * countQuery는 원래부터 파생 테이블 형태라 바뀐 것이 없다.
     *
     * 주의: findAllByKeywordWithFulltext와 마찬가지로 이 쿼리도 ORDER BY를 자체 포함한다.
     * Pageable에 Sort를 실어 넘기면 프로퍼티명이 컬럼명으로 변환되지 않은 채 append되어
     * "Unknown column 'q.createDate' in 'order clause'" (MySQL 1054)가 발생한다.
     * QuestionService.getList가 Sort 없는 PageRequest를 넘기는 이유다.
     */
    @Query(value = "SELECT q.ID FROM QUESTION q JOIN ("
            + "  SELECT q2.ID AS ID FROM QUESTION q2 "
            + "   WHERE MATCH(q2.SUBJECT, q2.CONTENT) AGAINST (:kw IN BOOLEAN MODE) "
            + "  UNION "
            + "  SELECT q3.ID FROM QUESTION q3 JOIN SITE_USER u1 ON q3.AUTHOR_ID = u1.ID "
            + "   WHERE u1.USERNAME LIKE %:kw% "
            + "  UNION "
            + "  SELECT a.QUESTION_ID FROM ANSWER a JOIN SITE_USER u2 ON a.AUTHOR_ID = u2.ID "
            + "   WHERE u2.USERNAME LIKE %:kw% "
            + ") t ON t.ID = q.ID ORDER BY q.CREATE_DATE DESC",
            countQuery = "SELECT COUNT(*) FROM ("
            + "  SELECT q2.ID FROM QUESTION q2 "
            + "   WHERE MATCH(q2.SUBJECT, q2.CONTENT) AGAINST (:kw IN BOOLEAN MODE) "
            + "  UNION "
            + "  SELECT q3.ID FROM QUESTION q3 JOIN SITE_USER u1 ON q3.AUTHOR_ID = u1.ID "
            + "   WHERE u1.USERNAME LIKE %:kw% "
            + "  UNION "
            + "  SELECT a.QUESTION_ID FROM ANSWER a JOIN SITE_USER u2 ON a.AUTHOR_ID = u2.ID "
            + "   WHERE u2.USERNAME LIKE %:kw% "
            + ") t",
            nativeQuery = true)
    Page<Long> findQuestionIdsByKeywordWithFulltext(@Param("kw") String kw, Pageable pageable);

    /**
     * STEP 2: ID 목록으로 질문 + 작성자를 한 번에 조회.
     *
     * ORDER BY를 반드시 유지해야 한다. IN 절은 인자 순서를 보장하지 않으므로
     * 이게 없으면 목록 정렬이 깨진다.
     */
    @Query("select q from Question q join fetch q.author where q.id in :ids order by q.createDate desc")
    List<Question> findAllWithAuthorByIdIn(@Param("ids") List<Long> ids);
}
