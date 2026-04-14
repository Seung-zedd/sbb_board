package com.mysite.sbb.trending;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface TrendingQuestionRepository extends JpaRepository<TrendingQuestion, Long> {

    // 읽기 경로: TREND_RANK 인덱스 스캔만 (조인 없음)
    @Query("SELECT t FROM TrendingQuestion t ORDER BY t.trendRank ASC")
    List<TrendingQuestion> findAllOrderByRank();

    // 배치 집계 쿼리: 최근 7일 질문 기준 answerCount + voterCount 집계
    @Query("""
            SELECT new com.mysite.sbb.trending.TrendingRawDto(
                q.id, q.subject, u.username,
                COUNT(DISTINCT a.id), COUNT(DISTINCT qv.id)
            )
            FROM Question q
            LEFT JOIN q.answerList a
            LEFT JOIN q.questionVoters qv
            LEFT JOIN q.author u
            WHERE q.createDate >= :since
            GROUP BY q.id, q.subject, u.username
            """)
    List<TrendingRawDto> findRawScoresSince(@Param("since") LocalDateTime since);
}
