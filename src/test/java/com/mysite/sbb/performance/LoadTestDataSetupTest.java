package com.mysite.sbb.performance;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Phase 3 k6 부하 테스트용 더미 데이터 생성
 *
 * performance 프로파일과의 차이:
 * - loadtest 프로파일 사용 → ddl-auto: update
 * - 테스트 완료 후 스키마와 데이터가 MySQL에 그대로 유지됨 (create-drop 아님)
 * - TRENDING_QUESTION 테이블도 함께 생성됨
 *
 * 실행 방법:
 * 1. 아래 @Disabled 주석 해제
 * 2. ./gradlew test --tests LoadTestDataSetupTest -Dspring.profiles.active=loadtest
 * 3. 완료 후 @Disabled 다시 추가
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("loadtest")
class LoadTestDataSetupTest {

    @Autowired
    private DummyDataGenerator dummyDataGenerator;

    @Test
    void setupLoadTestData() {
        log.info("===== Phase 3 k6 부하 테스트 데이터 생성 시작 =====");
        dummyDataGenerator.deleteAll();
        dummyDataGenerator.generateAll();  // 10K 질문, ~100K 답변
        log.info("===== 데이터 생성 완료 - MySQL에 데이터 유지됨 =====");
    }
}
