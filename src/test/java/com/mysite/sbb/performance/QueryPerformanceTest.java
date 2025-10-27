package com.mysite.sbb.performance;

import com.mysite.sbb.question.QuestionPerformanceRepository;
import com.mysite.sbb.question.dto.QuestionWithAnswerCountDto;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Semi Join vs Fetch Join 성능 비교 테스트
 *
 * 측정 항목:
 * 1. 쿼리 실행 시간 (ms)
 * 2. 쿼리 실행 횟수
 * 3. 전송 데이터량 (entity load count)
 * 4. 반복 테스트 평균값
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("performance")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QueryPerformanceTest {

    @Autowired
    private QuestionPerformanceRepository performanceRepository;

    @Autowired
    private DummyDataGenerator dummyDataGenerator;

    @Autowired
    private EntityManager entityManager;

    private Statistics statistics;

    @BeforeAll
    static void beforeAll() {
        log.info("========================================");
        log.info("  Semi Join vs Fetch Join 성능 테스트");
        log.info("========================================");
    }

    @BeforeEach
    void setUp() {
        // Hibernate Statistics 초기화
        SessionFactory sessionFactory = entityManager.getEntityManagerFactory()
                .unwrap(SessionFactory.class);
        statistics = sessionFactory.getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
    }

    /**
     * 테스트 0: 더미 데이터 생성
     * 최초 1회만 실행 (수동으로 @Disabled 제거 후 실행)
     */
    @Test
    @Order(0)
    @Disabled("더미 데이터 생성용 - 필요 시 주석 해제")
    void generateDummyData() {
        dummyDataGenerator.deleteAll();
        dummyDataGenerator.generateAll();
    }

    /**
     * 테스트 1: Semi Join 방식 성능 측정
     */
    @Test
    @Order(1)
    @Transactional
    void testSemiJoinPerformance() {
        log.info("\n========== [1] Semi Join 방식 테스트 ==========");

        int iterations = 10;
        List<Long> executionTimes = new ArrayList<>();

        for (int i = 0; i < iterations; i++) {
            statistics.clear();
            entityManager.clear();

            long startTime = System.currentTimeMillis();
            List<QuestionWithAnswerCountDto> result = performanceRepository
                    .findQuestionsWithAnswerCountBySemiJoin(0, 20);
            long endTime = System.currentTimeMillis();

            executionTimes.add(endTime - startTime);

            if (i == 0) {
                log.info("첫 번째 실행 결과:");
                log.info("  - 조회된 질문 수: {}", result.size());
                log.info("  - 실행 시간: {}ms", endTime - startTime);
                log.info("  - 쿼리 실행 횟수: {}", statistics.getPrepareStatementCount());
                log.info("  - Entity Load 횟수: {}", statistics.getEntityLoadCount());
            }
        }

        double avgTime = executionTimes.stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(0.0);

        log.info("\n[Semi Join 최종 결과]");
        log.info("  - 평균 실행 시간: {:.2f}ms", avgTime);
        log.info("  - 최소 실행 시간: {}ms", executionTimes.stream().min(Long::compareTo).orElse(0L));
        log.info("  - 최대 실행 시간: {}ms", executionTimes.stream().max(Long::compareTo).orElse(0L));
    }

    /**
     * 테스트 2: Fetch Join 방식 성능 측정
     */
    @Test
    @Order(2)
    @Transactional
    void testFetchJoinPerformance() {
        log.info("\n========== [2] Fetch Join 방식 테스트 ==========");

        int iterations = 10;
        List<Long> executionTimes = new ArrayList<>();

        for (int i = 0; i < iterations; i++) {
            statistics.clear();
            entityManager.clear();

            long startTime = System.currentTimeMillis();
            List<QuestionWithAnswerCountDto> result = performanceRepository
                    .findQuestionsWithAnswerCountByFetchJoin(0, 20);
            long endTime = System.currentTimeMillis();

            executionTimes.add(endTime - startTime);

            if (i == 0) {
                log.info("첫 번째 실행 결과:");
                log.info("  - 조회된 질문 수: {}", result.size());
                log.info("  - 실행 시간: {}ms", endTime - startTime);
                log.info("  - 쿼리 실행 횟수: {}", statistics.getPrepareStatementCount());
                log.info("  - Entity Load 횟수: {}", statistics.getEntityLoadCount());
            }
        }

        double avgTime = executionTimes.stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(0.0);

        log.info("\n[Fetch Join 최종 결과]");
        log.info("  - 평균 실행 시간: {:.2f}ms", avgTime);
        log.info("  - 최소 실행 시간: {}ms", executionTimes.stream().min(Long::compareTo).orElse(0L));
        log.info("  - 최대 실행 시간: {}ms", executionTimes.stream().max(Long::compareTo).orElse(0L));
    }

    /**
     * 테스트 3: Lazy Loading 방식 성능 측정 (N+1 문제)
     */
    @Test
    @Order(3)
    @Transactional
    void testLazyLoadingPerformance() {
        log.info("\n========== [3] Lazy Loading 방식 테스트 (N+1 문제) ==========");

        int iterations = 10;
        List<Long> executionTimes = new ArrayList<>();

        for (int i = 0; i < iterations; i++) {
            statistics.clear();
            entityManager.clear();

            long startTime = System.currentTimeMillis();
            List<QuestionWithAnswerCountDto> result = performanceRepository
                    .findQuestionsWithAnswerCountByLazyLoading(0, 20);
            long endTime = System.currentTimeMillis();

            executionTimes.add(endTime - startTime);

            if (i == 0) {
                log.info("첫 번째 실행 결과:");
                log.info("  - 조회된 질문 수: {}", result.size());
                log.info("  - 실행 시간: {}ms", endTime - startTime);
                log.info("  - 쿼리 실행 횟수: {}", statistics.getPrepareStatementCount());
                log.info("  - Entity Load 횟수: {}", statistics.getEntityLoadCount());
                log.warn("  ⚠️ N+1 문제 발생! 쿼리가 {}번 실행되었습니다.", statistics.getPrepareStatementCount());
            }
        }

        double avgTime = executionTimes.stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(0.0);

        log.info("\n[Lazy Loading 최종 결과]");
        log.info("  - 평균 실행 시간: {:.2f}ms", avgTime);
        log.info("  - 최소 실행 시간: {}ms", executionTimes.stream().min(Long::compareTo).orElse(0L));
        log.info("  - 최대 실행 시간: {}ms", executionTimes.stream().max(Long::compareTo).orElse(0L));
    }

    /**
     * 테스트 4: 종합 비교
     */
    @Test
    @Order(4)
    @Transactional
    void testComparisonSummary() {
        log.info("\n========== [4] 종합 비교 테스트 ==========");

        // 각 방식별 측정
        PerformanceResult semiJoinResult = measurePerformance("Semi Join",
                () -> performanceRepository.findQuestionsWithAnswerCountBySemiJoin(0, 20));

        PerformanceResult fetchJoinResult = measurePerformance("Fetch Join",
                () -> performanceRepository.findQuestionsWithAnswerCountByFetchJoin(0, 20));

        PerformanceResult lazyLoadingResult = measurePerformance("Lazy Loading",
                () -> performanceRepository.findQuestionsWithAnswerCountByLazyLoading(0, 20));

        // 결과 출력
        log.info("\n" + "=".repeat(80));
        log.info("최종 비교 결과");
        log.info("=".repeat(80));
        log.info(String.format("%-20s | %10s | %10s | %10s", "방식", "실행시간(ms)", "쿼리횟수", "Entity Load"));
        log.info("-".repeat(80));
        log.info(String.format("%-20s | %10d | %10d | %10d",
                "Semi Join", semiJoinResult.executionTime, semiJoinResult.queryCount, semiJoinResult.entityLoadCount));
        log.info(String.format("%-20s | %10d | %10d | %10d",
                "Fetch Join", fetchJoinResult.executionTime, fetchJoinResult.queryCount, fetchJoinResult.entityLoadCount));
        log.info(String.format("%-20s | %10d | %10d | %10d",
                "Lazy Loading (N+1)", lazyLoadingResult.executionTime, lazyLoadingResult.queryCount, lazyLoadingResult.entityLoadCount));
        log.info("=".repeat(80));

        // 결론
        log.info("\n[분석 결과]");
        if (semiJoinResult.executionTime < fetchJoinResult.executionTime) {
            double improvement = ((double)(fetchJoinResult.executionTime - semiJoinResult.executionTime)
                    / fetchJoinResult.executionTime) * 100;
            log.info("✅ Semi Join이 Fetch Join 대비 {:.1f}% 빠릅니다!", improvement);
        } else {
            double degradation = ((double)(semiJoinResult.executionTime - fetchJoinResult.executionTime)
                    / semiJoinResult.executionTime) * 100;
            log.info("⚠️ Fetch Join이 Semi Join 대비 {:.1f}% 빠릅니다.", degradation);
        }

        log.info("쿼리 횟수: Semi Join {}회 vs Fetch Join {}회", semiJoinResult.queryCount, fetchJoinResult.queryCount);
        log.info("Entity Load: Semi Join {}개 vs Fetch Join {}개", semiJoinResult.entityLoadCount, fetchJoinResult.entityLoadCount);
    }

    /**
     * 성능 측정 헬퍼 메서드
     */
    private PerformanceResult measurePerformance(String name, Runnable task) {
        statistics.clear();
        entityManager.clear();

        long startTime = System.currentTimeMillis();
        task.run();
        long endTime = System.currentTimeMillis();

        return new PerformanceResult(
                name,
                endTime - startTime,
                statistics.getPrepareStatementCount(),
                statistics.getEntityLoadCount()
        );
    }

    /**
     * 성능 측정 결과 DTO
     */
    private static class PerformanceResult {
        String name;
        long executionTime;
        long queryCount;
        long entityLoadCount;

        PerformanceResult(String name, long executionTime, long queryCount, long entityLoadCount) {
            this.name = name;
            this.executionTime = executionTime;
            this.queryCount = queryCount;
            this.entityLoadCount = entityLoadCount;
        }
    }
}
