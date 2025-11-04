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
 * 현재 설정: 5K 데이터셋
 * - 사용자: 1,000명
 * - 질문: 5,000개
 * - 답변: 평균 10개/질문 (총 약 50,000개)
 *
 * 다른 데이터셋 테스트 시 generateQuestions() 파라미터 변경:
 * - 1K: generateQuestions(1000, users)
 * - 5K: generateQuestions(5000, users)
 * - 10K: generateQuestions(10000, users)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DummyDataGenerator {

    private final UserRepository userRepository;
    private final QuestionRepository questionRepository;
    private final AnswerRepository answerRepository;

    private final Random random = new Random();

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
        List<Question> questions = generateQuestions(5000, users);
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
            Question question = new Question();
            question.setSubject("테스트 질문 제목 " + i);
            question.setContent("테스트 질문 내용입니다. 질문 번호: " + i + "\n".repeat(5));
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
    @Transactional
    public void deleteAll() {
        log.info("모든 데이터 삭제 중...");
        answerRepository.deleteAll();
        questionRepository.deleteAll();
        userRepository.deleteAll();
        log.info("모든 데이터 삭제 완료");
    }
}
