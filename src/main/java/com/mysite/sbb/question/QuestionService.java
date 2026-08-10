package com.mysite.sbb.question;

import com.mysite.sbb.answer.AnswerRepository;
import com.mysite.sbb.common.DataNotFoundException;
import com.mysite.sbb.question.dto.QuestionDetailDto;
import com.mysite.sbb.question.dto.QuestionListItemDto;
import com.mysite.sbb.user.SiteUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
@Slf4j
public class QuestionService {
    private final QuestionRepository questionRepository;
    private final QuestionVotersRepository questionVotersRepository;
    private final AnswerRepository answerRepository; // 목록 조회 시 답변 수 집계용 (세미 조인 STEP 3)

    public QuestionDetailDto getQuestionDto(Long id) {
        Question question = this.getQuestion(id);
        return QuestionDetailDto.from(question);
    }

    /**
     * 질문 목록을 세미 조인 3단계로 조회한다.
     *
     * STEP 1  질문 ID만 페이징 조회 (검색어 유무에 따라 분기)
     * STEP 2  IN 절 + JOIN FETCH로 질문 + 작성자를 한 번에 조회
     * STEP 3  답변 수를 GROUP BY로 한 번에 집계
     *
     * 이전에는 Page<Question>을 받아 곧바로 QuestionListItemDto::from에 넘겼는데,
     * from 내부에서 author 프록시와 answerList 컬렉션이 건별로 초기화되면서
     * 페이지당 최대 21쿼리가 나갔다. 지금은 페이지 크기와 무관하게 3쿼리로 고정된다.
     */
    public Page<QuestionListItemDto> getList(int page, String kw) {
        Pageable pageable;
        Page<Long> idPage;

        if (kw == null || kw.trim().isEmpty()) {
            // 검색어가 없으면 전체 조회 — JPQL 경로이므로 Sort의 프로퍼티명이 컬럼명으로 변환된다
            List<Sort.Order> sorts = new ArrayList<>();
            sorts.add(Sort.Order.desc("createDate"));
            pageable = PageRequest.of(page, 10, Sort.by(sorts));
            idPage = questionRepository.findAllQuestionIds(pageable);
        } else {
            // 검색어가 있으면 FULLTEXT 인덱스 검색.
            // findQuestionIdsByKeywordWithFulltext는 네이티브 쿼리로 ORDER BY q.CREATE_DATE DESC를 이미 포함한다.
            // 여기에 Sort를 넘기면 프로퍼티명(createDate)이 컬럼명으로 변환되지 않은 채 뒤에 append되어
            // "Unknown column 'q.createDate' in 'order clause'" (MySQL 1054)로 500이 발생한다.
            pageable = PageRequest.of(page, 10);
            idPage = questionRepository.findQuestionIdsByKeywordWithFulltext(kw, pageable);
        }

        List<Long> questionIds = idPage.getContent();
        if (questionIds.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, idPage.getTotalElements());
        }

        // STEP 2: 질문 + 작성자 (JOIN FETCH이므로 author 접근 시 추가 쿼리가 없다)
        List<Question> questions = questionRepository.findAllWithAuthorByIdIn(questionIds);

        // STEP 3: 답변 수 집계. 답변이 0건인 질문은 결과에 없으므로 조회 측에서 0으로 채운다.
        Map<Long, Long> answerCountMap = answerRepository.countByQuestionIdIn(questionIds).stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> (Long) row[1]
                ));

        List<QuestionListItemDto> items = questions.stream()
                .map(q -> QuestionListItemDto.from(
                        q,
                        answerCountMap.getOrDefault(q.getId(), 0L).intValue()
                ))
                .toList();

        // totalElements는 STEP 1의 Page에서 그대로 가져와야 페이지네이션이 유지된다.
        return new PageImpl<>(items, pageable, idPage.getTotalElements());
    }


    public Question getQuestion(Long id) {
        return questionRepository.findById(id).orElseThrow(() -> new DataNotFoundException("question not found"));
    }

    public void create(String subject, String content, SiteUser author) {
        Question q = new Question();
        q.setSubject(subject);
        q.setContent(content);
        q.setCreateDate(LocalDateTime.now());
        q.setAuthor(author);

        questionRepository.save(q);
    }


    public void modify(Question question, String subject, String content) {
        question.setSubject(subject);
        question.setContent(content);
        question.setModifyDate(LocalDateTime.now());
        questionRepository.save(question);
    }

    public void delete(Question question) {
        questionRepository.delete(question);
    }

    public void vote(Question question, SiteUser siteUser) {
        if (questionVotersRepository.existsByQuestionAndSiteUser(question, siteUser)) {
            throw new IllegalStateException("이미 추천한 사용자입니다.");
        }
        QuestionVoter questionVoter = new QuestionVoter();
        questionVoter.takeQuestion(question);
        questionVoter.takeSiteUser(siteUser);
        questionVotersRepository.save(questionVoter);
    }
}


