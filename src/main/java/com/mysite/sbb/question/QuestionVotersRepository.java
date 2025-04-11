package com.mysite.sbb.question;

import com.mysite.sbb.user.SiteUser;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionVotersRepository extends JpaRepository<QuestionVoter, Long> {
    boolean existsByQuestionAndSiteUser(Question question, SiteUser siteUser);

}
