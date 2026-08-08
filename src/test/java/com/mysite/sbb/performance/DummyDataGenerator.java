package com.mysite.sbb.performance;

import com.mysite.sbb.answer.Answer;
import com.mysite.sbb.answer.AnswerRepository;
import com.mysite.sbb.question.Question;
import com.mysite.sbb.question.QuestionRepository;
import com.mysite.sbb.user.SiteUser;
import com.mysite.sbb.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 성능 테스트용 더미 데이터 생성기
 *
 * 현재 설정: 10K 데이터셋
 * - 사용자: 1,000명
 * - 질문: 10,000개
 * - 답변: 질문당 5~20개 (총 약 125,000개)
 *
 * 다른 데이터셋 테스트 시 generateQuestions() 파라미터 변경:
 * - 1K: generateQuestions(1000, users)
 * - 5K: generateQuestions(5000, users)
 * - 10K: generateQuestions(10000, users)
 *
 * 검색 키워드 분포:
 * 부하 스크립트(load-test/scenario-a.js)가 검색하는 키워드를 제목/본문에 서로 다른
 * 비율로 심는다. 비율이 다른 이유는 FULLTEXT 인덱스의 효과가 선택도(selectivity)에
 * 따라 달라지기 때문이다. 모든 문서가 매칭되면(선택도 0) 인덱스를 타도 풀스캔과
 * 다를 바 없어 인덱스 효과를 측정할 수 없다.
 *
 * 이전 버전은 모든 질문이 "테스트 질문 제목 N" 형태였다. Phase 2(Semi Join vs
 * Fetch Join)는 건수만 필요해 문제가 없었으나, Phase 3에서 검색 시나리오를 추가하며
 * 이 데이터를 그대로 재사용한 탓에 scenario-a.js의 키워드 7개 중 6개가 0건 매칭,
 * 나머지 1개("테스트")는 전건 매칭이 되어 검색 부하 측정이 성립하지 않았다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DummyDataGenerator {

    /** 재현 가능한 데이터셋을 위한 고정 시드. 이 값이 같으면 매번 동일한 데이터가 생성된다. */
    private static final long SEED = 20260808L;

    /** load-test/scenario-a.js가 검색하는 키워드와 각 키워드가 질문에 등장할 확률(%) */
    private static final String[] SEARCH_KEYWORDS = {"자바", "스프링", "JPA", "쿼리", "오류", "성능", "테스트"};
    private static final int[] KEYWORD_PERCENT = {15, 12, 9, 7, 5, 3, 1};

    private final UserRepository userRepository;
    private final QuestionRepository questionRepository;
    private final AnswerRepository answerRepository;

    private final Random random = new Random(SEED);

    /**
     * 전체 더미 데이터 생성
     */
    @Transactional
    public void generateAll() {
        log.info("========== 더미 데이터 생성 시작 ==========");

        long startTime = System.currentTimeMillis();

        // 1. 사용자 생성
        log.info("사용자 생성 중...");
        List<SiteUser> users = generateUsers(1000);
        log.info("사용자 {} 명 생성 완료", users.size());

        // 2. 질문 생성
        //* 다른 더미 데이터셋으로 테스트를 하고 싶으면 generateQuestions의 count 인자를 바꿀 것
        log.info("질문 생성 중...");
        List<Question> questions = generateQuestions(10000, users);
        log.info("질문 {} 개 생성 완료", questions.size());

        // 3. 답변 생성
        log.info("답변 생성 중...");
        int totalAnswers = generateAnswers(questions, users);
        log.info("답변 {} 개 생성 완료", totalAnswers);

        long endTime = System.currentTimeMillis();
        log.info("========== 더미 데이터 생성 완료 ({}ms) ==========", endTime - startTime);
    }

    /**
     * 사용자 생성
     */
    private List<SiteUser> generateUsers(int count) {
        List<SiteUser> users = new ArrayList<>();

        for (int i = 1; i <= count; i++) {
            SiteUser user = new SiteUser();
            user.setUsername("user" + i);
            user.setEmail("user" + i + "@test.com");
            user.setPassword("password" + i);
            users.add(user);

            // 배치 처리 (100개씩)
            if (i % 100 == 0) {
                userRepository.saveAll(users);
                userRepository.flush();
                users.clear();
                log.debug("사용자 {} 명 저장 완료", i);
            }
        }

        if (!users.isEmpty()) {
            userRepository.saveAll(users);
            userRepository.flush();
        }

        return userRepository.findAll();
    }

    /**
     * 질문 생성
     */
    private List<Question> generateQuestions(int count, List<SiteUser> users) {
        List<Question> questions = new ArrayList<>();

        for (int i = 1; i <= count; i++) {
            List<String> keywords = pickKeywords();

            Question question = new Question();
            question.setSubject(buildSubject(i, keywords));
            question.setContent(buildContent(i, keywords));
            question.setCreateDate(LocalDateTime.now().minusDays(count - i));
            question.setAuthor(getRandomUser(users));
            questions.add(question);

            // 배치 처리 (100개씩)
            if (i % 100 == 0) {
                questionRepository.saveAll(questions);
                questionRepository.flush();
                questions.clear();
                log.debug("질문 {} 개 저장 완료", i);
            }
        }

        if (!questions.isEmpty()) {
            questionRepository.saveAll(questions);
            questionRepository.flush();
        }

        return questionRepository.findAll();
    }

    /**
     * 이 질문에 심을 키워드를 확률적으로 고른다.
     * 키워드마다 독립적으로 판정하므로 한 질문에 여러 개가 들어갈 수 있고,
     * 전체 데이터에서 각 키워드의 매칭 비율은 KEYWORD_PERCENT에 수렴한다.
     */
    private List<String> pickKeywords() {
        List<String> picked = new ArrayList<>();
        for (int k = 0; k < SEARCH_KEYWORDS.length; k++) {
            if (random.nextInt(100) < KEYWORD_PERCENT[k]) {
                picked.add(SEARCH_KEYWORDS[k]);
            }
        }
        return picked;
    }

    /**
     * 제목 생성.
     * 고정 문구에 "테스트"나 "질문"을 쓰지 않는다. 쓰면 두 단어가 전건 매칭이 되어
     * "테스트" 키워드의 선택도(1%)가 무너진다.
     */
    private String buildSubject(int i, List<String> keywords) {
        if (keywords.isEmpty()) {
            return "일반 문의 " + i;
        }
        return String.join(" ", keywords) + " 관련 문의 " + i;
    }

    private String buildContent(int i, List<String> keywords) {
        StringBuilder sb = new StringBuilder();
        sb.append("게시판에 올라온 문의 내용입니다. 번호: ").append(i);
        if (!keywords.isEmpty()) {
            sb.append("\n").append(String.join(", ", keywords)).append("에 대해 궁금한 점이 있습니다.");
        }
        sb.append("\n".repeat(5));
        return sb.toString();
    }

    /**
     * 답변 생성
     * 질문당 5~20개의 답변을 랜덤으로 생성
     */
    private int generateAnswers(List<Question> questions, List<SiteUser> users) {
        int totalCount = 0;
        List<Answer> answers = new ArrayList<>();

        for (int i = 0; i < questions.size(); i++) {
            Question question = questions.get(i);
            int answerCount = 5 + random.nextInt(16); // 5~20개

            for (int j = 0; j < answerCount; j++) {
                Answer answer = new Answer();
                answer.setContent("테스트 답변 내용입니다. 답변 번호: " + (totalCount + j + 1));
                answer.setCreateDate(question.getCreateDate().plusHours(j + 1));
                answer.setQuestion(question);
                answer.setAuthor(getRandomUser(users));
                answers.add(answer);
                totalCount++;
            }

            // 배치 처리 (500개씩)
            if (answers.size() >= 500) {
                answerRepository.saveAll(answers);
                answerRepository.flush();
                answers.clear();
                log.debug("답변 {} 개 저장 완료", totalCount);
            }
        }

        if (!answers.isEmpty()) {
            answerRepository.saveAll(answers);
            answerRepository.flush();
        }

        return totalCount;
    }

    /**
     * 랜덤 사용자 선택
     */
    private SiteUser getRandomUser(List<SiteUser> users) {
        return users.get(random.nextInt(users.size()));
    }

    /**
     * 전체 데이터 삭제
     */
    /**
     * 전체 데이터 삭제
     *
     * deleteAll()이 아니라 deleteAllInBatch()를 쓴다. deleteAll()은 엔티티를 전부 로드한 뒤
     * 건별 DELETE를 발행해, 답변 12만 건 기준으로 삭제에만 십수 분이 걸린다.
     * deleteAllInBatch()는 테이블당 DELETE 한 문장으로 끝난다.
     *
     * 주의: deleteAllInBatch()는 cascade를 무시하므로 조인 테이블(QUESTION_VOTER,
     * ANSWER_VOTER)은 지우지 않는다. 이 생성기는 투표 데이터를 만들지 않아 문제가 없지만,
     * 투표가 있는 DB에 쓰면 FK 위반이 난다.
     */
    @Transactional
    public void deleteAll() {
        log.info("모든 데이터 삭제 중...");
        answerRepository.deleteAllInBatch();
        questionRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
        log.info("모든 데이터 삭제 완료");
    }
}
