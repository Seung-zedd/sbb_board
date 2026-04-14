package com.mysite.sbb.trending;

/**
 * 배치 집계 쿼리 결과를 담는 중간 DTO
 * JPQL 생성자 표현식으로 직접 매핑됨
 */
public record TrendingRawDto(
        Long questionId,
        String subject,
        String authorUsername,
        long answerCount,
        long voterCount
) {}
