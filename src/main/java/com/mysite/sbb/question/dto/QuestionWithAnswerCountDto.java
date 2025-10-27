package com.mysite.sbb.question.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class QuestionWithAnswerCountDto {
    private Long id;
    private String subject;
    private String content;
    private LocalDateTime createDate;
    private String authorUsername;
    private Long answerCount;
}
