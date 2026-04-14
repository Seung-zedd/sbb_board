package com.mysite.sbb.configuration;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄러는 loadtest / prod 프로파일에서만 활성화
 * dev (H2) / performance (JUnit 테스트) 에서는 배치 미실행
 */
@Configuration
@EnableScheduling
@Profile({"loadtest", "prod"})
public class SchedulingConfig {
}
