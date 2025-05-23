package com.mysite.sbb.answer;

import com.mysite.sbb.common.DataNotFoundException;
import com.mysite.sbb.question.Question;
import com.mysite.sbb.user.SiteUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;

@RequiredArgsConstructor
@Service
public class AnswerService {
    private final AnswerRepository answerRepository;
    private final AnswerVotersRepository answerVotersRepository;

    public Answer create(Question question, String content, SiteUser author) {
        Answer answer = new Answer();
        answer.setQuestion(question);
        answer.setCreateDate(LocalDateTime.now());
        answer.setContent(content);
        answer.setAuthor(author);
        answerRepository.save(answer);

        return answer;
    }

    public Answer getAnswer(Long id) {
        return answerRepository.findById(id).orElseThrow(() -> new DataNotFoundException("answer not found"));
    }

    public void modify(Answer answer, String content) {
        answer.setContent(content);
        answer.setModifyDate(LocalDateTime.now());
        answerRepository.save(answer);
    }

    public void delete(Answer answer) {
        answerRepository.delete(answer);
    }

    public void vote(Answer answer, SiteUser siteUser) {
        if (answerVotersRepository.existsByAnswerAndSiteUser(answer, siteUser)) {
            throw new IllegalStateException("이미 추천한 사용자입니다.");
        }
        AnswerVoter answerVoter = new AnswerVoter();
        answerVoter.takeAnswer(answer);
        answerVoter.takeSiteUser(siteUser);
        answerRepository.save(answer);
        answerVotersRepository.save(answerVoter);
    }


}
