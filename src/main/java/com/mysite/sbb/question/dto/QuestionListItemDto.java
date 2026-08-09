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

    /**
     * 답변 수를 외부에서 주입받아 DTO를 만든다.
     *
     * 이전에는 from(Question) 하나만 두고 내부에서 question.getAnswerList().size()를
     * 호출했는데, 목록 10건마다 답변 컬렉션 전체가 지연 로딩되면서 N+1이 발생했다
     * (2026-08-08 부하 테스트에서 답변 조회 62,796회 / 131초).
     * 여기서는 getAnswerList()를 건드리지 않는다 —
     * 답변 수는 AnswerRepository.countByQuestionIdIn이 GROUP BY로 한 번에 집계한다.
     *
     * author는 QuestionRepository.findAllWithAuthorByIdIn이 JOIN FETCH로 미리 초기화하므로
     * 여기서 getAuthor()를 호출해도 추가 쿼리가 나가지 않는다.
     */
    public static QuestionListItemDto from(Question question, int answerCount) {
        //* Optional로 감싼 객체를 반환할 때는 RestController를 만들어서 프론트한테 null값을 명시할 때 좋음
        if (question == null) {
            return null;
        }
        return QuestionListItemDto.builder()
                .id(question.getId())
                .subject(question.getSubject())
                .author(SiteUserDto.from(question.getAuthor()))
                .createDate(question.getCreateDate())
                .answerCount(answerCount)
                .build();
    }

}
