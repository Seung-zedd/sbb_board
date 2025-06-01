package com.mysite.sbb.question.dto;

import com.mysite.sbb.answer.dto.AnswerDto;
import com.mysite.sbb.question.Question;
import com.mysite.sbb.user.dto.SiteUserDto;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@NoArgsConstructor // 나중에 JSON 포맷 데이터를 클라이언트로 전달하기 위함
public class QuestionDetailDto {
    private Long id; // 번호나 상세 페이지 링크를 위해 필요할 수 있음
    private String subject; // 제목
    private String content; // 내용
    private SiteUserDto author; // 글쓴이
    private LocalDateTime createDate; // 생성일자
    private LocalDateTime modifyDate; // 수정날짜
    private List<AnswerDto> answerList; // 답변 리스트
    private int answerCount; // 답변 수
    private int voteCount; // 질문 추천 수

    @Builder
    private QuestionDetailDto(Long id, String subject, String content, SiteUserDto author, LocalDateTime createDate, LocalDateTime modifyDate, List<AnswerDto> answerList, int answerCount, int voteCount) {
        this.id = id;
        this.subject = subject;
        this.content = content;
        this.author = author;
        this.createDate = createDate;
        this.modifyDate = modifyDate;
        this.answerList = answerList;
        this.answerCount = answerCount;
        this.voteCount = voteCount;
    }

    public static QuestionDetailDto from(Question question) {
        if (question == null) {
            return null;
        }
        return QuestionDetailDto
                .builder()
                .id(question.getId())
                .subject(question.getSubject())
                .content(question.getContent())
                .author(SiteUserDto.from(question.getAuthor()))
                .createDate(question.getCreateDate())
                .modifyDate(question.getModifyDate())
                .answerList(question.getAnswerList()
                        .stream()
                        .map(AnswerDto::from)
                        .toList())
                .answerCount(question.getAnswerList() != null ? question.getAnswerList().size() : 0)
                .voteCount(question.getQuestionVoters() != null ? question.getQuestionVoters().size() : 0)
                .build();
    }

}
