# mysql:8.0 + 클라이언트 문자셋 고정
#
# charset.cnf를 바인드 마운트로 넣으면 Windows 호스트에서 파일 권한이 항상 777이 되고,
# MySQL은 world-writable 설정 파일을 보안상 무시한다:
#
#   mysql: [Warning] World-writable config file '/etc/mysql/conf.d/charset.cnf' is ignored.
#
# 그래서 마운트 대신 이미지에 구워 넣고 644로 고정한다.
FROM mysql:8.0

COPY conf.d/charset.cnf /etc/mysql/conf.d/charset.cnf
RUN chmod 644 /etc/mysql/conf.d/charset.cnf

# 데이터 디렉터리가 비어 있을 때만 실행된다(기존 볼륨에는 수동 적용 필요).
COPY initdb/ /docker-entrypoint-initdb.d/
