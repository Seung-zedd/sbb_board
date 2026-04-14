package com.mysite.sbb.trending;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 주간 인기글 랭킹 스냅샷 테이블
 *
 * 설계 원칙:
 * - Question FK 없음 → 읽기 시 조인 제로 (단순 인덱스 스캔만)
 * - @Scheduled 배치가 1시간마다 전체 교체 (delete + insert)
 * - 목적: 실시간 GROUP BY + 다중 조인의 DB CPU 스파이크를 쓰기 경로로 격리
 */
@Getter
@Entity
@NoArgsConstructor
@Table(indexes = {
        @Index(name = "idx_trending_trend_rank", columnList = "TREND_RANK")
})
public class TrendingQuestion {

    @Id
    @Column(name = "QUESTION_ID")
    private Long questionId;

    @Column(length = 200)
    private String subject;

    @Column(length = 100)
    private String authorUsername;

    private long answerCount;
    private long voterCount;
    private double score;       // answerCount * 1.0 + voterCount * 2.0

    @Column(name = "TREND_RANK")
    private int trendRank;      // 1~50

    private LocalDateTime calculatedAt;

    @Builder
    public TrendingQuestion(Long questionId, String subject, String authorUsername,
                            long answerCount, long voterCount, double score,
                            int trendRank, LocalDateTime calculatedAt) {
        this.questionId = questionId;
        this.subject = subject;
        this.authorUsername = authorUsername;
        this.answerCount = answerCount;
        this.voterCount = voterCount;
        this.score = score;
        this.trendRank = trendRank;
        this.calculatedAt = calculatedAt;
    }
}
