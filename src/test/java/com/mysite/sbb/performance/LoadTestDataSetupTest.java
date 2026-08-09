package com.mysite.sbb.performance;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
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
 * 1. 아래 @Disabled를 주석 처리
 * 2. ./gradlew test --tests LoadTestDataSetupTest -Dspring.profiles.active=loadtest
 * 3. 완료 후 @Disabled 주석 해제 (반드시)
 *
 * @Disabled가 없으면 ./gradlew test만 돌려도 기존 데이터를 전부 지우고
 * 12만 건을 재생성한다(약 10분 소요). 2026-08-09에 누락된 것을 발견해 복구했다.
 */
@Slf4j
@Disabled("부하 테스트 데이터 생성 전용 - 수동 실행 시에만 주석 처리할 것")
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
