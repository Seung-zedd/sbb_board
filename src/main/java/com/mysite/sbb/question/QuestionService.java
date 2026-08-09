package com.mysite.sbb.question;

import com.mysite.sbb.common.DataNotFoundException;
import com.mysite.sbb.question.dto.QuestionDetailDto;
import com.mysite.sbb.question.dto.QuestionListItemDto;
import com.mysite.sbb.user.SiteUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
@Service
@Slf4j
public class QuestionService {
    private final QuestionRepository questionRepository;
    private final QuestionVotersRepository questionVotersRepository;

    public QuestionDetailDto getQuestionDto(Long id) {
        Question question = this.getQuestion(id);
        return QuestionDetailDto.from(question);
    }

    public Page<QuestionListItemDto> getList(int page, String kw) {
        Page<Question> questionPage;
        if (kw == null || kw.trim().isEmpty()) {
            // 검색어가 없으면 전체 조회 — JPQL 경로이므로 Sort의 프로퍼티명이 컬럼명으로 변환된다
            List<Sort.Order> sorts = new ArrayList<>();
            sorts.add(Sort.Order.desc("createDate"));
            questionPage = questionRepository.findAll(PageRequest.of(page, 10, Sort.by(sorts)));
        } else {
            // 검색어가 있으면 FULLTEXT 인덱스 검색.
            // findAllByKeywordWithFulltext는 네이티브 쿼리로 ORDER BY q.CREATE_DATE DESC를 이미 포함한다.
            // 여기에 Sort를 넘기면 프로퍼티명(createDate)이 컬럼명으로 변환되지 않은 채 뒤에 append되어
            // "Unknown column 'q.createDate' in 'order clause'" (MySQL 1054)로 500이 발생한다.
            questionPage = questionRepository.findAllByKeywordWithFulltext(kw, PageRequest.of(page, 10));
        }

        return questionPage.map(QuestionListItemDto::from);
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


