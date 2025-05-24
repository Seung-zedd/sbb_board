package com.mysite.sbb.answer.dto;

import com.mysite.sbb.answer.Answer;
import com.mysite.sbb.user.dto.SiteUserDto;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
public class AnswerDto {
    private Long id;
    private String content; // 답변 내용
    private SiteUserDto author; // 답변 작성자 정보
    private LocalDateTime createDate; // 답변 생성일시
    private LocalDateTime modifyDate; // 답변 수정일시
    private int voteCount; // 답변 추천 수

    @Builder
    private AnswerDto(Long id, String content, SiteUserDto author, LocalDateTime createDate, LocalDateTime modifyDate, int voteCount) {
        this.id = id;
        this.content = content;
        this.author = author;
        this.createDate = createDate;
        this.modifyDate = modifyDate;
        this.voteCount = voteCount;
    }

    public static AnswerDto from(Answer answer) {
        if (answer == null) {
            return null;
        }
        return AnswerDto.builder()
                .id(answer.getId())
                .content(answer.getContent())
                .author(SiteUserDto.from(answer.getAuthor())) // SiteUserDto 사용
                .createDate(answer.getCreateDate())
                .modifyDate(answer.getModifyDate())
                .voteCount(answer.getAnswerVoters() != null ? answer.getAnswerVoters().size() : 0) // 추천 수 매핑 (null 체크 포함)
                .build();

    }

}
