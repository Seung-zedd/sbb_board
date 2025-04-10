package com.mysite.sbb.answer;

import com.mysite.sbb.question.Question;
import com.mysite.sbb.user.SiteUser;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Set;

@Getter
@Setter
@Entity
public class Answer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(columnDefinition = "TEXT")
    private String content;

    private LocalDateTime createDate;

    @ManyToOne(fetch = FetchType.LAZY)
    private Question question;

    @ManyToOne(fetch = FetchType.LAZY)
    private SiteUser author; // 사용자 1명이 답변 여러 개 작성(로그인 정보를 확인한 후 답변을 생성하기 때문에 사용자 여러 명은 말이 안됨!)
    private LocalDateTime modifyDate;

    @OneToMany(mappedBy = "answer")
    private Set<AnswerVoter> answerVoters;

}
