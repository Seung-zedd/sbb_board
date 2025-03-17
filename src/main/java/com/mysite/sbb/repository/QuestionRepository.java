package com.mysite.sbb.repository;

import com.mysite.sbb.domain.Question;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionRepository extends JpaRepository<Question, Long> {
    // findById만 기본적으로 제공해주므로 새로 작성
    Question findBySubject(String subject);
    Question findBySubjectAndContent(String subject, String content);
}
