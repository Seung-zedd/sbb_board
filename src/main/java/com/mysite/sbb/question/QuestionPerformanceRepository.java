package com.mysite.sbb.question;

import com.mysite.sbb.question.dto.QuestionWithAnswerCountDto;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 성능 테스트용 Repository
 * Semi Join vs Fetch Join 비교
 */
@Repository
@RequiredArgsConstructor
public class QuestionPerformanceRepository {

    private final EntityManager em;

    /**
     * 방법 1: Semi Join + Stream API
     *
     * 데이터베이스론의 세미 조인 연산 적용:
     * 1. 필요한 ID만 먼저 조회 (projection)
     * 2. IN 절로 실제 데이터 조회
     * 3. 별도 쿼리로 집계 데이터 조회
     *
     * 장점: 중복 데이터 전송 없음, 네트워크 비용 절감
     * 단점: 쿼리 3번 실행
     */
    public List<QuestionWithAnswerCountDto> findQuestionsWithAnswerCountBySemiJoin(int pageNumber, int pageSize) {
        int offset = pageNumber * pageSize;

        // STEP 1: 질문 ID만 조회 (세미 조인의 핵심 - 참가 키만 추출)
        List<Long> questionIds = em.createQuery(
                "SELECT q.id FROM Question q ORDER BY q.createDate DESC", Long.class)
                .setFirstResult(offset)
                .setMaxResults(pageSize)
                .getResultList();

        if (questionIds.isEmpty()) {
            return List.of();
        }

        // STEP 2: IN 절로 질문 + 작성자 조회 (실제 필요한 데이터만)
        List<Question> questions = em.createQuery(
                "SELECT q FROM Question q " +
                "JOIN FETCH q.author " +
                "WHERE q.id IN :ids " +
                "ORDER BY q.createDate DESC", Question.class)
                .setParameter("ids", questionIds)
                .getResultList();

        // STEP 3: IN 절로 답변 개수 집계 조회
        List<Object[]> answerCountResults = em.createQuery(
                "SELECT a.question.id, COUNT(a) " +
                "FROM Answer a " +
                "WHERE a.question.id IN :ids " +
                "GROUP BY a.question.id", Object[].class)
                .setParameter("ids", questionIds)
                .getResultList();

        // Stream API로 Map 생성
        Map<Long, Long> answerCountMap = answerCountResults.stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> (Long) row[1]
                ));

        // DTO 조립
        return questions.stream()
                .map(q -> new QuestionWithAnswerCountDto(
                        q.getId(),
                        q.getSubject(),
                        q.getContent(),
                        q.getCreateDate(),
                        q.getAuthor().getUsername(),
                        answerCountMap.getOrDefault(q.getId(), 0L)
                ))
                .collect(Collectors.toList());
    }

    /**
     * 방법 2: Fetch Join (일반적인 방법)
     *
     * 장점: 쿼리 1번으로 모든 데이터 조회
     * 단점: 카테시안 곱으로 인한 중복 데이터 전송
     *       - Question이 Answer 개수만큼 중복 전송됨
     *       - 페이징 시 메모리에서 처리 (setMaxResults 적용 안됨)
     */
    public List<QuestionWithAnswerCountDto> findQuestionsWithAnswerCountByFetchJoin(int pageNumber, int pageSize) {
        int offset = pageNumber * pageSize;

        List<Question> questions = em.createQuery(
                "SELECT DISTINCT q FROM Question q " +
                "LEFT JOIN FETCH q.answerList a " +
                "LEFT JOIN FETCH q.author " +
                "ORDER BY q.createDate DESC", Question.class)
                .setFirstResult(offset)
                .setMaxResults(pageSize)
                .getResultList();

        return questions.stream()
                .map(q -> new QuestionWithAnswerCountDto(
                        q.getId(),
                        q.getSubject(),
                        q.getContent(),
                        q.getCreateDate(),
                        q.getAuthor().getUsername(),
                        (long) (q.getAnswerList() != null ? q.getAnswerList().size() : 0)
                ))
                .collect(Collectors.toList());
    }

    /**
     * 방법 3: Lazy Loading (N+1 문제 발생 - 비교용)
     *
     * 최악의 경우: Question 조회 1번 + Answer Count 조회 N번
     */
    public List<QuestionWithAnswerCountDto> findQuestionsWithAnswerCountByLazyLoading(int pageNumber, int pageSize) {
        int offset = pageNumber * pageSize;

        List<Question> questions = em.createQuery(
                "SELECT q FROM Question q " +
                "JOIN FETCH q.author " +
                "ORDER BY q.createDate DESC", Question.class)
                .setFirstResult(offset)
                .setMaxResults(pageSize)
                .getResultList();

        // N+1 문제 발생: answerList.size() 호출 시마다 쿼리 실행
        return questions.stream()
                .map(q -> new QuestionWithAnswerCountDto(
                        q.getId(),
                        q.getSubject(),
                        q.getContent(),
                        q.getCreateDate(),
                        q.getAuthor().getUsername(),
                        (long) (q.getAnswerList() != null ? q.getAnswerList().size() : 0)
                ))
                .collect(Collectors.toList());
    }
}
