package com.mysite.sbb.answer;

import com.mysite.sbb.user.SiteUser;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Setter;

@Entity
public class AnswerVoter {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @Setter(AccessLevel.NONE)
    private Answer answer;

    @ManyToOne(fetch = FetchType.LAZY)
    @Setter(AccessLevel.NONE)
    private SiteUser siteUser;

    public void takeAnswer(Answer answer) {
        this.answer = answer;
        answer.getAnswerVoters().add(this);
    }

    public void takeSiteUser(SiteUser siteUser) {
        this.siteUser = siteUser;
        siteUser.getAnswerVoters().add(this);
    }
}
