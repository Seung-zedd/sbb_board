package com.mysite.sbb.question;

import com.mysite.sbb.user.SiteUser;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Setter;

@Entity
public class QuestionVoter {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @Setter(AccessLevel.NONE)
    private Question question;

    @ManyToOne(fetch = FetchType.LAZY)
    @Setter(AccessLevel.NONE)
    private SiteUser siteUser;

    //* 컬렉션 쪽은 읽기만 하기 때문에 양방향일 때 연관관계 편의 메서드 사용
    public void takeQuestion(Question question) {
        this.question = question;
        question.getQuestionVoters().add(this);
    }

    public void takeSiteUser(SiteUser siteUser) {
        this.siteUser = siteUser;
        siteUser.getQuestionVoters().add(this);
    }

}
