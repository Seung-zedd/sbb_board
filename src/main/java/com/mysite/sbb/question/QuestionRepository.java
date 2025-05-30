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
}
