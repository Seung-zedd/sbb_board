package com.mysite.sbb.answer;

import com.mysite.sbb.user.SiteUser;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnswerVotersRepository extends JpaRepository<AnswerVoter, Long> {
    boolean existsByAnswerAndSiteUser(Answer answer, SiteUser siteUser);
}
