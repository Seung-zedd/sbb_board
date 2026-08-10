SELECT
  ROUND(SUM_TIMER_WAIT/1000000000, 1) AS total_ms,
  COUNT_STAR AS execs,
  ROUND(AVG_TIMER_WAIT/1000000, 2) AS avg_ms,
  ROUND(SUM_ROWS_EXAMINED/COUNT_STAR) AS rows_examined_avg,
  LEFT(DIGEST_TEXT, 120) AS query
FROM performance_schema.events_statements_summary_by_digest
WHERE SCHEMA_NAME = 'sbb_db'
ORDER BY SUM_TIMER_WAIT DESC
LIMIT 8;
