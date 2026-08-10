package com.mysite.sbb.answer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AnswerRepository extends JpaRepository<Answer, Long> {

    /**
     * 세미 조인 STEP 3: 질문 ID 목록에 대한 답변 수를 한 번에 집계한다.
     *
     * 기존에는 QuestionListItemDto.from이 question.getAnswerList().size()를 호출해
     * 목록 10건마다 답변 컬렉션을 통째로 초기화했다(질문 1만 건에 답변 12만 건이므로
     * 페이지당 평균 125행을 읽고 버렸다). 여기서는 COUNT만 가져온다.
     *
     * 반환 형식은 Object[]{questionId(Long), count(Long)}.
     * 답변이 0건인 질문은 GROUP BY 결과에 아예 포함되지 않으므로
     * 호출 측에서 기본값 0을 채워야 한다.
     */
    @Query("select a.question.id, count(a) from Answer a where a.question.id in :ids group by a.question.id")
    List<Object[]> countByQuestionIdIn(@Param("ids") List<Long> ids);
}
