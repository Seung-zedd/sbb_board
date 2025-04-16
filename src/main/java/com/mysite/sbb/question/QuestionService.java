package com.mysite.sbb.question;

import com.mysite.sbb.answer.Answer;
import com.mysite.sbb.common.DataNotFoundException;
import com.mysite.sbb.user.SiteUser;
import jakarta.persistence.criteria.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.io.Serial;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
@Service
public class QuestionService {
    private final QuestionRepository questionRepository;
    private final QuestionVotersRepository questionVotersRepository;

    private Specification<Question> search(String kw) {
        return new Specification<>() {
            @Serial
            private static final long serialVersionUID = 1L;

            @Override
            public Predicate toPredicate(Root<Question> q, CriteriaQuery<?> query, CriteriaBuilder cb) {
                query.distinct(true);
                Join<Question, SiteUser> u1 = q.join("author", JoinType.LEFT);
                Join<Question, Answer> a = q.join("answerList", JoinType.LEFT);
                Join<Answer, SiteUser> u2 = a.join("author", JoinType.LEFT);
                return
                        cb.or(cb.like(q.get("subject"), "%" + kw + "%"), // 제목
                                cb.like(q.get("content"), "%" + kw + "%"), // 내용
                                cb.like(u1.get("username"), "%" + kw + "%"), // 질문 작성
                                cb.like(a.get("content"), "%" + kw + "%"), // 답변 내용
                                cb.like(u2.get("username"), "%" + kw + "%")); // 답변 작성

            }
        };
    }

    public Page<Question> getList(int page, String kw) {
        List<Sort.Order> sorts = new ArrayList<>();
        sorts.add(Sort.Order.desc("createDate"));
        // 한 페이지에 10개의 데이터를 최신순으로 보여줌
        Pageable pageable = PageRequest.of(page, 10, Sort.by(sorts));
        Specification<Question> spec = search(kw);
        return questionRepository.findAll(spec, pageable);
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
        questionRepository.save(question);
        questionVotersRepository.save(questionVoter);
    }
}


