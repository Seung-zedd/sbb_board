package com.mysite.sbb.trending;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/question")
@RequiredArgsConstructor
public class TrendingQuestionController {

    private final TrendingQuestionService trendingQuestionService;

    /**
     * 주간 인기글 TOP 50 조회
     *
     * 읽기 경로: trending_question 테이블 단순 인덱스 스캔 (조인 없음)
     * k6 시나리오 B 부하 타겟 엔드포인트
     */
    @GetMapping("/trending")
    public ResponseEntity<List<TrendingQuestion>> getTrending() {
        return ResponseEntity.ok(trendingQuestionService.getTop50());
    }
}
