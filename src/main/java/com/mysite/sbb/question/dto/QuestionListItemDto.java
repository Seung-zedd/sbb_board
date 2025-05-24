package com.mysite.sbb.question.dto;

import com.mysite.sbb.question.Question;
import com.mysite.sbb.user.dto.SiteUserDto;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor // 나중에 JSON 포맷 데이터를 클라이언트로 전달하기 위함
public class QuestionListItemDto {
    private Long id; // 번호
    private String subject; // 제목
    private SiteUserDto author; // 글쓴이
    private LocalDateTime createDate; // 작성일시
    private int answerCount; // 답변 수

    @Builder
    private QuestionListItemDto(Long id, String subject, SiteUserDto author, LocalDateTime createDate, int answerCount) {
        this.id = id;
        this.subject = subject;
        this.author = author;
        this.createDate = createDate;
        this.answerCount = answerCount;
    }

    public static QuestionListItemDto from(Question question) {
        //* Optional로 감싼 객체를 반환할 때는 RestController를 만들어서 프론트한테 null값을 명시할 때 좋음
        if (question == null) {
            return null;
        }
        return QuestionListItemDto.builder()
                .id(question.getId())
                .subject(question.getSubject())
                .author(SiteUserDto.from(question.getAuthor()))
                .createDate(question.getCreateDate())
                .answerCount(question.getAnswerList() != null ? question.getAnswerList().size() : 0)
                .build();
    }

}
