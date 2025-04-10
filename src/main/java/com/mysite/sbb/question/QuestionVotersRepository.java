package com.mysite.sbb.question;

import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionVotersRepository extends JpaRepository<QuestionVoter, Long> {

}
