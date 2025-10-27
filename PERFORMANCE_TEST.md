# Semi Join vs Fetch Join 성능 비교 테스트

## 개요

데이터베이스론의 **세미 조인(Semi Join) 연산**을 JPA 쿼리 최적화에 적용하여, 전통적인 Fetch Join 방식과 성능을 비교합니다.

### 핵심 아이디어

분산 데이터베이스에서 네트워크 비용을 줄이기 위해 사용하는 세미 조인 개념을:
```
1. 참가 키(조인에 필요한 ID)만 먼저 조회
2. 해당 키로 실제 데이터 조회
3. 중복 데이터 전송 방지
```

JPA의 N+1 문제와 Fetch Join의 카테시안 곱 문제를 동시에 해결하는 방법으로 적용했습니다.

## 비교 대상

### 1. Semi Join + Stream API (제안 방식)
```java
// STEP 1: ID만 조회 (페이징 가능)
List<Long> ids = em.createQuery("SELECT q.id FROM Question q", Long.class)
    .setMaxResults(20).getResultList();

// STEP 2: IN 절로 실제 데이터 조회
List<Question> questions = em.createQuery(
    "SELECT q FROM Question q JOIN FETCH q.author WHERE q.id IN :ids", Question.class)
    .setParameter("ids", ids).getResultList();

// STEP 3: 집계 데이터 별도 조회
Map<Long, Long> answerCounts = em.createQuery(
    "SELECT a.question.id, COUNT(a) FROM Answer a WHERE a.question.id IN :ids GROUP BY a.question.id")
    .getResultList().stream().collect(Collectors.toMap(...));
```

**장점:**
- 중복 데이터 전송 없음
- 페이징이 DB 레벨에서 정확히 작동
- 네트워크 비용 절감

**단점:**
- 쿼리 3번 실행

### 2. Fetch Join (일반적인 방식)
```java
List<Question> questions = em.createQuery(
    "SELECT DISTINCT q FROM Question q " +
    "LEFT JOIN FETCH q.answerList " +
    "LEFT JOIN FETCH q.author", Question.class)
    .setMaxResults(20).getResultList();
```

**장점:**
- 쿼리 1번으로 모든 데이터 조회

**단점:**
- 카테시안 곱으로 중복 데이터 대량 전송
- 페이징이 메모리에서 처리됨 (성능 저하)
- Question이 Answer 개수만큼 중복 전송

### 3. Lazy Loading (N+1 문제 발생)
```java
List<Question> questions = em.createQuery(
    "SELECT q FROM Question q JOIN FETCH q.author", Question.class)
    .setMaxResults(20).getResultList();

// answerList.size() 호출 시마다 쿼리 실행
questions.forEach(q -> q.getAnswerList().size()); // N+1 발생!
```

## 테스트 환경

### 데이터 규모
- 사용자: 1,000명
- 질문: 10,000개
- 답변: 평균 10개/질문 (총 약 100,000개)
- 조회: 20개씩 페이징

### 측정 항목
1. **쿼리 실행 시간** (ms)
2. **쿼리 실행 횟수**
3. **Entity Load 횟수** (전송 데이터량)
4. **반복 테스트 평균값** (10회 실행)

## 실행 방법

### 1. Docker MySQL 실행
```bash
docker-compose up -d
```

MySQL이 `localhost:3307`에서 실행됩니다.

### 2. 더미 데이터 생성 (최초 1회만)

`QueryPerformanceTest.java`에서 다음 테스트의 `@Disabled` 주석을 제거하고 실행:

```java
@Test
@Disabled("더미 데이터 생성용 - 필요 시 주석 해제")
void generateDummyData() {
    dummyDataGenerator.deleteAll();
    dummyDataGenerator.generateAll();
}
```

또는 Gradle 명령어로 실행:
```bash
./gradlew test --tests QueryPerformanceTest.generateDummyData -Dspring.profiles.active=performance
```

### 3. 성능 테스트 실행

전체 테스트 실행:
```bash
./gradlew test --tests QueryPerformanceTest -Dspring.profiles.active=performance
```

개별 테스트 실행:
```bash
# Semi Join 테스트만
./gradlew test --tests QueryPerformanceTest.testSemiJoinPerformance -Dspring.profiles.active=performance

# Fetch Join 테스트만
./gradlew test --tests QueryPerformanceTest.testFetchJoinPerformance -Dspring.profiles.active=performance

# 종합 비교
./gradlew test --tests QueryPerformanceTest.testComparisonSummary -Dspring.profiles.active=performance
```

### 4. Docker 종료
```bash
docker-compose down
```

데이터를 유지하고 싶으면:
```bash
docker-compose stop
```

데이터까지 삭제하려면:
```bash
docker-compose down -v
```

## 예상 결과

### 케이스 1: 답변이 많은 경우 (평균 10개 이상)
```
========================================
방식                | 실행시간(ms) | 쿼리횟수 | Entity Load
----------------------------------------
Semi Join          |      150     |     3    |    20
Fetch Join         |      400     |     1    |   200
Lazy Loading       |      500     |    21    |    20
========================================

✅ Semi Join이 Fetch Join 대비 62.5% 빠릅니다!
```

**분석:**
- Semi Join: Question 20개 + 작성자 20명 = 40개 엔티티 로드
- Fetch Join: Question 20개가 답변 10개씩 중복 전송 = 200개 엔티티 로드
- 네트워크 비용이 5배 차이!

### 케이스 2: 답변이 적은 경우 (평균 1~2개)
```
========================================
방식                | 실행시간(ms) | 쿼리횟수 | Entity Load
----------------------------------------
Semi Join          |      120     |     3    |    20
Fetch Join         |      100     |     1    |    40
Lazy Loading       |      300     |    21    |    20
========================================

⚠️ Fetch Join이 Semi Join 대비 16.7% 빠릅니다.
```

**분석:**
- 답변이 적으면 카테시안 곱의 영향이 적음
- 쿼리 1번 vs 3번의 차이가 더 큰 영향
- 이 경우 Fetch Join이 유리할 수 있음

## 결론

### Semi Join이 유리한 경우
- ✅ 일대다 관계에서 "다" 쪽 데이터가 많은 경우 (답변 5개 이상)
- ✅ 페이징이 필요한 목록 조회
- ✅ 네트워크 비용이 중요한 경우
- ✅ 집계 데이터만 필요한 경우 (전체 데이터 불필요)

### Fetch Join이 유리한 경우
- ✅ 일대다 관계에서 "다" 쪽 데이터가 적은 경우 (답변 1~2개)
- ✅ 연관 데이터를 모두 사용해야 하는 경우
- ✅ 쿼리 횟수를 최소화해야 하는 경우

### 포트폴리오 어필 포인트
1. **학술적 개념의 실무 적용**: 데이터베이스론의 세미 조인을 JPA에 적용
2. **정량적 성능 분석**: 실제 측정을 통한 객관적 비교
3. **트레이드오프 이해**: 상황에 따른 최적 전략 선택 능력
4. **문제 해결 능력**: N+1 문제와 Fetch Join의 한계를 동시에 해결

## 파일 구조

```
sbb/
├── docker-compose.yml                          # Docker MySQL 설정
├── src/main/resources/
│   └── application-performance.yml             # 성능 테스트용 설정
├── src/main/java/com/mysite/sbb/
│   └── question/
│       ├── QuestionPerformanceRepository.java  # 3가지 방식 구현
│       └── dto/
│           └── QuestionWithAnswerCountDto.java # 결과 DTO
└── src/test/java/com/mysite/sbb/
    └── performance/
        ├── DummyDataGenerator.java             # 더미 데이터 생성
        └── QueryPerformanceTest.java           # 성능 테스트
```

## 참고 자료

- [Hibernate Statistics API](https://docs.jboss.org/hibernate/orm/6.0/userguide/html_single/Hibernate_User_Guide.html#statistics)
- [JPA Fetch Join의 한계](https://vladmihalcea.com/hibernate-facts-the-importance-of-fetch-strategy/)
- Database Systems: Semi-Join Operation
