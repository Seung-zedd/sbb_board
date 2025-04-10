package com.mysite.sbb.user;

import com.mysite.sbb.answer.AnswerVoter;
import com.mysite.sbb.question.QuestionVoter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.Set;

@Entity
@Getter
@Setter
public class SiteUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String username;

    private String password;

    @Column(unique = true)
    private String email;

    @OneToMany(mappedBy = "siteUser")
    private Set<QuestionVoter> questionVoters;

    @OneToMany(mappedBy = "siteUser")
    private Set<AnswerVoter> answerVoters;
}
