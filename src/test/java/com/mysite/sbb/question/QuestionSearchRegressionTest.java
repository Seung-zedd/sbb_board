package com.mysite.sbb.question;

import com.mysite.sbb.answer.AnswerRepository;
import com.mysite.sbb.question.dto.QuestionListItemDto;
import com.mysite.sbb.user.SiteUser;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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

    @Autowired
    private QuestionRepository questionRepository;

    @Autowired
    private AnswerRepository answerRepository;

    @Autowired
    private EntityManager entityManager;

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

    // ────────────────────────────────────────────────────────────────────
    // N+1 제거(세미 조인 3단계 전환) 회귀 테스트
    //
    // getList는 이제 Page<Question>을 그대로 map하지 않고
    // id 페이징 -> JOIN FETCH -> COUNT GROUP BY 세 단계를 직접 조립한다.
    // 조립 과정에서 깨지기 쉬운 세 가지를 고정한다.
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("answerCount가 실제 답변 수와 일치한다 (세미 조인 STEP 3 집계 검증)")
    void getList_answerCount_matchesActualAnswerCount() {
        Page<QuestionListItemDto> page = questionService.getList(0, "");

        assertThat(page.getContent()).isNotEmpty();
        for (QuestionListItemDto item : page.getContent()) {
            long expected = answerRepository.countByQuestionIdIn(List.of(item.getId())).stream()
                    .map(row -> (Long) row[1])
                    .findFirst()
                    .orElse(0L);

            assertThat((long) item.getAnswerCount())
                    .as("questionId=%d의 answerCount", item.getId())
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("답변이 0건인 질문의 answerCount는 0이다 (GROUP BY 결과 누락 시 NPE 방지)")
    void getList_questionWithoutAnswer_hasZeroAnswerCount() {
        // 현재 부하 테스트 데이터에는 답변 0건인 질문이 존재하지 않아
        // getOrDefault 분기가 실행되지 않는다. 여기서 직접 하나 만들어 덮는다.
        // (@Transactional이므로 테스트 종료 시 롤백된다)
        SiteUser author = questionRepository.findAll(PageRequest.of(0, 1)).getContent()
                .get(0).getAuthor();
        questionService.create("답변 없는 질문 - 회귀 테스트", "내용", author);

        Page<QuestionListItemDto> page = questionService.getList(0, "");

        QuestionListItemDto created = page.getContent().stream()
                .filter(item -> "답변 없는 질문 - 회귀 테스트".equals(item.getSubject()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("생성한 질문이 목록 첫 페이지에 없다"));

        assertThat(created.getAnswerCount()).isZero();
    }

    @Test
    @DisplayName("id만 뽑는 새 UNION 쿼리가 기존 엔티티 UNION 쿼리와 같은 결과를 낸다")
    void findQuestionIds_matchesLegacyEntityQuery() {
        // N+1 개선에서 STEP 1을 SELECT q.* -> SELECT q.ID로 바꿨다.
        // WHERE/UNION/ORDER BY는 그대로 두었으므로 결과 집합이 동일해야 한다.
        // 기존 findAllByKeywordWithFulltext는 이 비교를 위해 남겨둔 것이다.
        for (String kw : List.of("테스트", "질문", "자바", "스프링", "JPA")) {
            Page<Question> legacy = questionRepository
                    .findAllByKeywordWithFulltext(kw, PageRequest.of(0, 10));
            Page<Long> current = questionRepository
                    .findQuestionIdsByKeywordWithFulltext(kw, PageRequest.of(0, 10));

            assertThat(current.getTotalElements())
                    .as("kw=%s 의 전체 건수", kw)
                    .isEqualTo(legacy.getTotalElements());

            assertThat(current.getContent())
                    .as("kw=%s 의 1페이지 id 목록(순서 포함)", kw)
                    .isEqualTo(legacy.getContent().stream().map(Question::getId).toList());
        }
    }

    @Test
    @DisplayName("목록 1페이지 조회가 고정된 쿼리 수로 끝난다 (N+1 재발 방지)")
    void getList_executesFixedNumberOfQueries() {
        // 개선 전: id/엔티티 조회 1 + author 프록시 10 + answerList 컬렉션 10 = 최대 21회
        // 개선 후: id 페이징 1 + count 1 + JOIN FETCH 1 + COUNT GROUP BY 1 = 4회
        // 페이지 크기(10)를 늘려도 늘지 않는다는 점이 핵심이므로 여유를 두고 6으로 고정한다.
        assertThat(countQueries(() -> questionService.getList(0, "")))
                .as("빈 검색어 경로")
                .isLessThanOrEqualTo(6);

        assertThat(countQueries(() -> questionService.getList(0, KEYWORD)))
                .as("키워드 검색 경로")
                .isLessThanOrEqualTo(6);
    }

    private long countQueries(Runnable action) {
        SessionFactory sessionFactory = entityManager.getEntityManagerFactory()
                .unwrap(SessionFactory.class);
        Statistics statistics = sessionFactory.getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        // 1차 캐시에 남은 엔티티가 지연 로딩을 건너뛰게 만들면 측정이 왜곡된다.
        entityManager.clear();

        action.run();

        return statistics.getPrepareStatementCount();
    }

    @Test
    @DisplayName("totalElements가 STEP 1의 count 결과로 유지된다 (페이지네이션 보존)")
    void getList_totalElements_isPreserved() {
        Page<QuestionListItemDto> firstPage = questionService.getList(0, "");
        Page<QuestionListItemDto> secondPage = questionService.getList(1, "");

        // 페이지 내용은 10건이지만 전체 건수는 데이터셋 전체여야 한다.
        assertThat(firstPage.getTotalElements()).isGreaterThan(10);
        assertThat(secondPage.getTotalElements()).isEqualTo(firstPage.getTotalElements());
        assertThat(firstPage.getContent().get(0).getId())
                .isNotEqualTo(secondPage.getContent().get(0).getId());
    }
}
