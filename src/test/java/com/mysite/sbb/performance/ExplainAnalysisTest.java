package com.mysite.sbb.performance;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 시나리오 A: 커버링 인덱스 + FULLTEXT 인덱스 적용 전후 EXPLAIN 분석
 *
 * 사전 조건:
 *   - MySQL sbb_db에 10K 더미 데이터가 있어야 합니다.
 *   - (없다면 먼저 LoadTestDataSetupTest.setupLoadTestData() 실행)
 *
 * 실행:
 *   ./gradlew test --tests ExplainAnalysisTest -Dspring.profiles.active=loadtest
 *
 * 결과 스크린샷 저장 위치: assets/images/
 *   - explain-before-full-scan.png    : [1-A] 실행 결과
 *   - explain-before-keyword.png      : [1-B] 실행 결과
 *   - explain-after-full-scan.png     : [3-A] 실행 결과
 *   - explain-after-keyword.png       : [3-B] 실행 결과
 *
 * 측정 지표:
 *   - type  : ALL(풀스캔) → index(커버링) / fulltext(FULLTEXT 인덱스)
 *   - key   : NULL(인덱스 미사용) → 인덱스명
 *   - Extra : "Using filesort" 제거 여부
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("loadtest")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ExplainAnalysisTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ── BEFORE: LIKE %kw% (앞 와일드카드 → 인덱스 무효화 baseline) ──────────────

    private static final String BEFORE_FULL_SCAN =
            "SELECT DISTINCT q.* FROM QUESTION q" +
            " ORDER BY q.CREATE_DATE DESC LIMIT 10 OFFSET 0";

    private static final String BEFORE_KEYWORD_SEARCH =
            "SELECT DISTINCT q.* FROM QUESTION q" +
            " LEFT JOIN SITE_USER u1 ON q.AUTHOR_ID = u1.ID" +
            " LEFT JOIN ANSWER a     ON a.QUESTION_ID = q.ID" +
            " LEFT JOIN SITE_USER u2 ON a.AUTHOR_ID = u2.ID" +
            " WHERE q.SUBJECT   LIKE '%스프링%'" +
            "    OR q.CONTENT   LIKE '%스프링%'" +
            "    OR u1.USERNAME LIKE '%스프링%'" +
            "    OR a.CONTENT   LIKE '%스프링%'" +
            "    OR u2.USERNAME LIKE '%스프링%'" +
            " ORDER BY q.CREATE_DATE DESC LIMIT 10 OFFSET 0";

    // ── AFTER: 커버링 인덱스 + FULLTEXT MATCH AGAINST ────────────────────────────

    private static final String AFTER_FULL_SCAN =
            "SELECT DISTINCT q.* FROM QUESTION q" +
            " ORDER BY q.CREATE_DATE DESC LIMIT 10 OFFSET 0";

    private static final String AFTER_KEYWORD_SEARCH =
            "SELECT DISTINCT q.* FROM QUESTION q" +
            " LEFT JOIN SITE_USER u1 ON q.AUTHOR_ID = u1.ID" +
            " LEFT JOIN ANSWER a     ON a.QUESTION_ID = q.ID" +
            "        AND MATCH(a.CONTENT) AGAINST ('스프링' IN BOOLEAN MODE)" +
            " LEFT JOIN SITE_USER u2 ON a.AUTHOR_ID = u2.ID" +
            " WHERE MATCH(q.SUBJECT, q.CONTENT) AGAINST ('스프링' IN BOOLEAN MODE)" +
            "    OR u1.USERNAME LIKE '%스프링%'" +
            "    OR u2.USERNAME LIKE '%스프링%'" +
            " ORDER BY q.CREATE_DATE DESC LIMIT 10 OFFSET 0";

    // FULLTEXT 인덱스 단독 검증용 (OR LIKE 없이 순수 FULLTEXT 성능 측정)
    private static final String AFTER_FULLTEXT_ONLY =
            "SELECT DISTINCT q.* FROM QUESTION q" +
            " WHERE MATCH(q.SUBJECT, q.CONTENT) AGAINST ('스프링' IN BOOLEAN MODE)" +
            " ORDER BY q.CREATE_DATE DESC LIMIT 10 OFFSET 0";

    // ─────────────────────────────────────────────────────────────────────────────

    @BeforeAll
    static void prerequisiteNotice() {
        log.info("\n" + "=".repeat(72));
        log.info("  Phase 3 시나리오 A — EXPLAIN 분석 (커버링 인덱스 + FULLTEXT)");
        log.info("  전제 조건: MySQL sbb_db에 10K 더미 데이터가 있어야 합니다.");
        log.info("  스크린샷 저장 위치: assets/images/");
        log.info("=".repeat(72));
    }

    /**
     * 1단계: 인덱스 없는 상태의 실행 계획 (baseline)
     * - 예상: type=ALL, Using filesort, 100K+ rows scan
     * → 스크린샷: assets/images/explain-before-full-scan.png
     *             assets/images/explain-before-keyword.png
     */
    @Test
    @Order(1)
    void explainBeforeIndex() {
        log.info("\n" + "=".repeat(72));
        log.info("  [BEFORE] 인덱스 적용 전 실행 계획");
        log.info("=".repeat(72));

        log.info("\n[1-A] 전체 목록 조회");
        log.info("      예상: type=ALL, Extra=Using filesort");
        printExplain(BEFORE_FULL_SCAN);

        log.info("\n[1-B] 키워드 검색 (LIKE %%스프링%%)");
        log.info("      예상: type=ALL × 4개 테이블, Using temporary + Using filesort");
        printExplain(BEFORE_KEYWORD_SEARCH);
    }

    /**
     * 2단계: 인덱스 생성
     * - idx_question_covering : ORDER BY + 페이지네이션 커버링 인덱스
     * - ft_idx_question_search: QUESTION.SUBJECT, CONTENT FULLTEXT (ngram)
     * - ft_idx_answer_content : ANSWER.CONTENT FULLTEXT (ngram)
     */
    @Test
    @Order(2)
    void createIndexes() {
        log.info("\n" + "=".repeat(72));
        log.info("  [INDEX] 인덱스 생성");
        log.info("=".repeat(72));

        createIndex(
                "ALTER TABLE QUESTION ADD INDEX IF NOT EXISTS idx_question_covering" +
                " (CREATE_DATE DESC, ID, SUBJECT, AUTHOR_ID)",
                "idx_question_covering");

        createIndex(
                "ALTER TABLE QUESTION ADD FULLTEXT INDEX IF NOT EXISTS ft_idx_question_search" +
                " (SUBJECT, CONTENT) WITH PARSER ngram",
                "ft_idx_question_search");

        createIndex(
                "ALTER TABLE ANSWER ADD FULLTEXT INDEX IF NOT EXISTS ft_idx_answer_content" +
                " (CONTENT) WITH PARSER ngram",
                "ft_idx_answer_content");
    }

    /**
     * 3단계: 인덱스 적용 후 실행 계획
     * - 예상: type=index (커버링), type=fulltext (MATCH AGAINST)
     * → 스크린샷: assets/images/explain-after-full-scan.png
     *             assets/images/explain-after-keyword.png
     */
    @Test
    @Order(3)
    void explainAfterIndex() {
        log.info("\n" + "=".repeat(72));
        log.info("  [AFTER] 인덱스 적용 후 실행 계획");
        log.info("=".repeat(72));

        log.info("\n[3-A] 전체 목록 조회 — 커버링 인덱스 적용");
        log.info("      예상: type=index, key=idx_question_covering, Extra=Using index (filesort 제거)");
        printExplain(AFTER_FULL_SCAN);

        log.info("\n[3-B] 키워드 검색 — FULLTEXT 인덱스 적용 (MATCH AGAINST + OR LIKE)");
        log.info("      예상: QUESTION type=fulltext, key=ft_idx_question_search");
        log.info("      ※ OR u.USERNAME LIKE %%...%% 조건으로 일부 플랜이 변경될 수 있음");
        printExplain(AFTER_KEYWORD_SEARCH);

        log.info("\n[3-C] 순수 FULLTEXT 검색 — OR LIKE 없이 FULLTEXT 인덱스 단독 성능");
        log.info("      예상: type=fulltext, key=ft_idx_question_search");
        printExplain(AFTER_FULLTEXT_ONLY);
    }

    /**
     * 4단계: 실행 계획 개선 assertion
     * - [검증 1] 커버링 인덱스 → key != NULL && Extra에 "Using filesort" 없음
     * - [검증 2] 순수 FULLTEXT → type = "fulltext"
     */
    @Test
    @Order(4)
    void assertIndexImprovements() {
        log.info("\n" + "=".repeat(72));
        log.info("  [ASSERT] 인덱스 개선 검증");
        log.info("=".repeat(72));

        // [검증 1] 커버링 인덱스: filesort 제거
        List<Map<String, Object>> fullScanPlan = runExplain(AFTER_FULL_SCAN);
        Map<String, Object> qRow = findRow(fullScanPlan, "q");

        assertThat(qRow.get("key"))
                .as("[전체 목록] 커버링 인덱스가 사용되어야 합니다 (key=NULL이면 인덱스 미적용)")
                .isNotNull();
        log.info("✅ 커버링 인덱스 사용 확인: key={}", qRow.get("key"));

        String extra = String.valueOf(qRow.getOrDefault("Extra", ""));
        assertThat(extra)
                .as("[전체 목록] Using filesort가 제거되어야 합니다 (현재 Extra: %s)", extra)
                .doesNotContain("Using filesort");
        log.info("✅ Using filesort 제거 확인: Extra={}", extra.isBlank() ? "(없음)" : extra);

        // [검증 2] 순수 FULLTEXT: type=fulltext
        List<Map<String, Object>> fulltextPlan = runExplain(AFTER_FULLTEXT_ONLY);
        Map<String, Object> ftRow = findRow(fulltextPlan, "q");

        assertThat(String.valueOf(ftRow.get("type")))
                .as("[FULLTEXT 검색] QUESTION 테이블 type이 fulltext이어야 합니다")
                .isEqualTo("fulltext");
        log.info("✅ FULLTEXT 인덱스 사용 확인: type={}, key={}", ftRow.get("type"), ftRow.get("key"));

        log.info("\n" + "=".repeat(72));
        log.info("  모든 인덱스 개선 검증 통과! ✅");
        log.info("=".repeat(72));
    }

    // ── private helpers ───────────────────────────────────────────────────────────

    private void printExplain(String sql) {
        List<Map<String, Object>> rows = runExplain(sql);
        log.info(String.format("  %-8s | %-10s | %-8s | %-30s | %s",
                "table", "type", "rows", "key", "Extra"));
        log.info("  " + "-".repeat(86));
        for (Map<String, Object> row : rows) {
            log.info(String.format("  %-8s | %-10s | %-8s | %-30s | %s",
                    row.getOrDefault("table", ""),
                    row.getOrDefault("type", ""),
                    row.getOrDefault("rows", ""),
                    row.getOrDefault("key", "NULL"),
                    row.getOrDefault("Extra", "")));
        }
    }

    private List<Map<String, Object>> runExplain(String sql) {
        return jdbcTemplate.queryForList("EXPLAIN " + sql);
    }

    private Map<String, Object> findRow(List<Map<String, Object>> plan, String tableAlias) {
        return plan.stream()
                .filter(row -> tableAlias.equals(String.valueOf(row.get("table"))))
                .findFirst()
                .orElseGet(() -> plan.get(0));
    }

    private void createIndex(String sql, String indexName) {
        try {
            jdbcTemplate.execute(sql);
            log.info("✅ 인덱스 생성: {}", indexName);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("Duplicate key name")) {
                log.info("⏭️  이미 존재 (건너뜀): {}", indexName);
            } else {
                log.error("❌ 인덱스 생성 실패: {} — {}", indexName, e.getMessage());
                throw e;
            }
        }
    }
}
