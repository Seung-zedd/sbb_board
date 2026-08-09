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
}
