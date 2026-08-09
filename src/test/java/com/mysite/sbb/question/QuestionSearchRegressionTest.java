package com.mysite.sbb.question;

import com.mysite.sbb.question.dto.QuestionListItemDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Phase 4 회귀 테스트 — 키워드 검색 경로의 ORDER BY 중복 버그
 *
 * 증상: getList(page, kw)가 kw != "" 일 때 HTTP 500 (MySQL 1054)
 * 원인: findAllByKeywordWithFulltext는 네이티브 쿼리로 "ORDER BY q.CREATE_DATE DESC"를
 *       이미 포함하는데, Pageable에 담긴 Sort(createDate)가 뒤에 한 번 더 append된다.
 *       네이티브 쿼리는 프로퍼티명 -> 컬럼명 변환이 없으므로 그대로 q.createDate로 나가
 *       "Unknown column 'q.createDate' in 'order clause'"가 발생한다.
 *
 * 근거: results/error_evidence_q_createdate.txt
 *       (2026-08-07 HikariCP 풀 실험 4회차 전부에서 요청의 약 50%가 이 예외로 실패)
 *
 * 전제: loadtest 프로파일 = 로컬 MySQL(sbb_db) + index-setup.sql로 생성한 ngram FULLTEXT 인덱스.
 *       MATCH AGAINST는 H2에서 동작하지 않으므로 실제 MySQL이 필요하다.
 */
@SpringBootTest
@ActiveProfiles("loadtest")
@Transactional  // 웹 요청은 OSIV로 영속성 컨텍스트가 열려 있다. answerCount(lazy) 접근 조건을 맞춘다.
class QuestionSearchRegressionTest {

    @Autowired
    private QuestionService questionService;

    /**
     * DummyDataGenerator가 생성하는 제목/내용은 "테스트 질문 제목 N" / "테스트 질문 내용입니다. 질문 번호: N"
     * 형태뿐이라, 실제로 FULLTEXT에 매칭되는 키워드는 "테스트", "질문", "내용", 숫자 정도다.
     * (부하 스크립트 load-test/scenario-a.js가 쓰는 "자바", "스프링", "JPA" 등은 0건 매칭 — 별도 이슈)
     */
    private static final String KEYWORD = "테스트";

    @Test
    @DisplayName("키워드 검색이 SQL 예외 없이 반환된다 (MySQL 1054 회귀 방지)")
    void getList_withKeyword_doesNotThrowSqlException() {
        assertThatCode(() -> questionService.getList(0, KEYWORD))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("키워드 검색 결과가 작성일시 내림차순으로 정렬된다")
    void getList_withKeyword_isSortedByCreateDateDesc() {
        Page<QuestionListItemDto> page = questionService.getList(0, KEYWORD);

        List<LocalDateTime> createDates = page.getContent().stream()
                .map(QuestionListItemDto::getCreateDate)
                .toList();

        assertThat(createDates).isNotEmpty();
        assertThat(createDates).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    @DisplayName("빈 검색어 경로는 기존대로 동작한다 (수정으로 깨지지 않았는지 확인)")
    void getList_withoutKeyword_stillWorks() {
        Page<QuestionListItemDto> page = questionService.getList(0, "");

        assertThat(page.getContent()).hasSize(10);
        assertThat(page.getContent().stream().map(QuestionListItemDto::getCreateDate).toList())
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }
}
