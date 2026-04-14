package com.mysite.sbb.trending;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class TrendingQuestionService {

    private static final int TOP_N = 50;

    private final TrendingQuestionRepository trendingQuestionRepository;

    /**
     * 매 1시간마다 주간 인기글 TOP 50을 재계산하여 trending_question 테이블에 교체 저장.
     *
     * 전략: delete-all + insert (upsert 대신 단순 교체)
     * - 랭킹은 항상 전체가 교체되므로 부분 upsert보다 단순하고 정합성 보장
     * - 1시간 주기 배치이므로 잠깐의 empty 상태는 허용 (eventual consistency)
     */
    @Scheduled(fixedRate = 3_600_000)
    @Transactional
    public void refreshTrendingQuestions() {
        LocalDateTime since = LocalDateTime.now().minusDays(7);
        LocalDateTime calculatedAt = LocalDateTime.now();

        // 1. 집계 쿼리 실행 (이 쿼리만 복잡한 JOIN + GROUP BY 수행)
        List<TrendingRawDto> rawList = trendingQuestionRepository.findRawScoresSince(since);

        // 2. 가중치 스코어 계산 후 정렬 (answerCount×1 + voterCount×2)
        List<TrendingRawDto> sorted = rawList.stream()
                .sorted(Comparator.comparingDouble(
                        (TrendingRawDto r) -> r.answerCount() * 1.0 + r.voterCount() * 2.0
                ).reversed())
                .limit(TOP_N)
                .toList();

        // 3. 기존 랭킹 전체 삭제
        trendingQuestionRepository.deleteAllInBatch();

        // 4. 새 TOP 50 저장
        List<TrendingQuestion> newRanking = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            TrendingRawDto raw = sorted.get(i);
            double score = raw.answerCount() * 1.0 + raw.voterCount() * 2.0;
            newRanking.add(TrendingQuestion.builder()
                    .questionId(raw.questionId())
                    .subject(raw.subject())
                    .authorUsername(raw.authorUsername())
                    .answerCount(raw.answerCount())
                    .voterCount(raw.voterCount())
                    .score(score)
                    .trendRank(i + 1)
                    .calculatedAt(calculatedAt)
                    .build());
        }
        trendingQuestionRepository.saveAll(newRanking);

        log.info("[Trending Batch] 랭킹 갱신 완료: {}건 | 기준: 최근 7일 | 산출 시각: {}", sorted.size(), calculatedAt);
    }

    @Transactional(readOnly = true)
    public List<TrendingQuestion> getTop50() {
        return trendingQuestionRepository.findAllOrderByRank();
    }
}
