package com.mysite.sbb;

import com.mysite.sbb.answer.Answer;
import com.mysite.sbb.question.Question;
import com.mysite.sbb.answer.AnswerRepository;
import com.mysite.sbb.question.QuestionRepository;
import com.mysite.sbb.question.QuestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class SbbApplicationTests {

	// JUnit은 생성자를 통한 객체 주입을 지원하지 않기 때문에 테스트 환경에서만 Autowired 사용 가능
	@Autowired
	private QuestionRepository questionRepository;
	@Autowired
	private AnswerRepository answerRepository;
    @Autowired
    private QuestionService questionService;

	@Test
	void testJpa() {
		for (int i = 1; i <= 300; i++) {
			String subject = String.format("테스트 데이터입니다:[%03d]", i);
			String content = "내용무";
			questionService.create(subject, content);
		}
	}



	@Test
	void shouldFindQuestionData() {
		List<Question> all = this.questionRepository.findAll();
		assertEquals(2, all.size());

		Question q = all.getFirst();
		assertEquals("sbb가 무엇인가요?", q.getSubject());
	}

	@Test
	void findById() {
		Optional<Question> result = this.questionRepository.findById(1L);
		//? Optional 타입은 아래와 같이 코드를 짜야하나?
		if (result.isPresent()) {
			Question q = result.get();
			assertEquals("sbb가 무엇인가요?", q.getSubject());
		}
	}

	@Test
	void findBySubject() {
		Question q = this.questionRepository.findBySubjectAndContent("sbb가 무엇인가요?", "sbb에 대해서 알고 싶습니다.");
		assertEquals(1, q.getId());
	}

	@Test
	void findBySubjectLike() {
		List<Question> questionList = this.questionRepository.findBySubjectLike("%무엇%");
		assertEquals("sbb가 무엇인가요?", questionList.getFirst().getSubject());
	}

	@Test
	void updateSubject() {
		Optional<Question> oq = this.questionRepository.findById(1L);
		assertTrue(oq.isPresent());
		Question q = oq.get();
		q.setSubject("수정된 제목");
		this.questionRepository.save(q);
	}

	@Test
	void deleteQuestionData() {
		assertEquals(2, this.questionRepository.count());
		Optional<Question> oq = this.questionRepository.findById(1L);
		assertTrue(oq.isPresent());
		Question q = oq.get();
		this.questionRepository.delete(q);
		assertEquals(1, this.questionRepository.count());
	}

	//* 답변 데이터 테스트
	@Test
	void shouldCreateAnswer() {
		Optional<Question> oq = this.questionRepository.findById(2L);
		assertTrue(oq.isPresent());
		Question q = oq.get();

		Answer a = new Answer();
		a.setContent("네 자동으로 생성됩니다.");
		a.setQuestion(q); // 어떤 질문의 답변인지 알기 위함
		a.setCreateDate(LocalDateTime.now());
		this.answerRepository.save(a);
	}

	@Test
	void shouldFindCertainAnswer() {
		Optional<Answer> oa = this.answerRepository.findById(1L);
		assertTrue(oa.isPresent());
		Answer a = oa.get();
		assertEquals(2, a.getQuestion().getId());
	}

	@Test
	@Transactional
	void shouldFindAnswerViaLinkedQuestion() {
		Optional<Question> oq = this.questionRepository.findById(2L); //! 리포지토리는 EntityManger로 관리되기 때문에 여기서 DB 세션이 끊어짐
		assertTrue(oq.isPresent());
		Question q = oq.get();

		List<Answer> answerList = q.getAnswerList();

		assertEquals(1, answerList.size());
		assertEquals("네 자동으로 생성됩니다.", answerList.getFirst().getContent());
	}
}
