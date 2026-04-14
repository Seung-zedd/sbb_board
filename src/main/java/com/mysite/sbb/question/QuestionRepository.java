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
    // MATCH AGAINST: ft_idx_question_search(SUBJECT, CONTENT), ft_idx_answer_content(CONTENT)
    // 한글 지원: ngram 파서 적용 필요 (index-setup.sql 참고)
    @Query(value = "SELECT DISTINCT q.* FROM QUESTION q "
            + "LEFT JOIN SITE_USER u1 ON q.AUTHOR_ID = u1.ID "
            + "LEFT JOIN ANSWER a ON a.QUESTION_ID = q.ID "
            + "LEFT JOIN SITE_USER u2 ON a.AUTHOR_ID = u2.ID "
            + "WHERE MATCH(q.SUBJECT, q.CONTENT) AGAINST (:kw IN BOOLEAN MODE) "
            + "   OR u1.USERNAME LIKE %:kw% "
            + "   OR u2.USERNAME LIKE %:kw% "
            + "ORDER BY q.CREATE_DATE DESC",
            countQuery = "SELECT COUNT(DISTINCT q.ID) FROM QUESTION q "
            + "LEFT JOIN SITE_USER u1 ON q.AUTHOR_ID = u1.ID "
            + "LEFT JOIN ANSWER a ON a.QUESTION_ID = q.ID "
            + "LEFT JOIN SITE_USER u2 ON a.AUTHOR_ID = u2.ID "
            + "WHERE MATCH(q.SUBJECT, q.CONTENT) AGAINST (:kw IN BOOLEAN MODE) "
            + "   OR u1.USERNAME LIKE %:kw% "
            + "   OR u2.USERNAME LIKE %:kw%",
            nativeQuery = true)
    Page<Question> findAllByKeywordWithFulltext(@Param("kw") String kw, Pageable pageable);
}
