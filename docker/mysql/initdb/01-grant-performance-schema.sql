-- 앱 계정(sbbtest)에 performance_schema 조회 권한을 부여한다.
--
-- 부하 테스트 후 어떤 쿼리가 시간을 먹었는지 보려면
-- performance_schema.events_statements_summary_by_digest를 읽어야 하는데,
-- 기본 상태에서는 root만 읽을 수 있어 매번 root로 접속해야 했다.
--
--   ERROR 1142 (42000): SELECT command denied to user 'sbbtest'@'localhost'
--                       for table 'events_statements_summary_by_digest'
--
-- 읽기 전용 권한이므로 로컬 성능 실험 환경에서 부여해도 안전하다.
-- (운영 DB에는 적용하지 말 것 — 쿼리 텍스트가 노출된다)
--
-- 주의: 이 스크립트는 데이터 디렉터리가 비어 있을 때만 실행된다.
-- 기존 볼륨에는 수동으로 적용해야 한다:
--   docker exec -i sbb-performance-test-db mysql -uroot -ptest1234 < 이 파일

GRANT SELECT ON performance_schema.* TO 'sbbtest'@'%';
FLUSH PRIVILEGES;
