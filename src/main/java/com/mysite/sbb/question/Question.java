package com.mysite.sbb.question;

import com.mysite.sbb.answer.Answer;
import com.mysite.sbb.user.SiteUser;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Getter
@Setter
@Entity
public class Question {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 200)
    private String subject; // 제목

    @Column(columnDefinition = "TEXT")
    private String content; // 내용

    private LocalDateTime createDate;

    @OneToMany(mappedBy = "question", cascade = CascadeType.REMOVE)
    private List<Answer> answerList;

    @ManyToOne(fetch = FetchType.LAZY)
    private SiteUser author; // 사용자 1명이 질문 여러 개 작성
    private LocalDateTime modifyDate;

    @OneToMany(mappedBy = "question")
    private Set<QuestionVoter> questionVoters;

}
