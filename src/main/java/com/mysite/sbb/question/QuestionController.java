package com.mysite.sbb.question;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RequestMapping("/question")
@RequiredArgsConstructor
@Controller
@Slf4j
public class QuestionController {
    private final QuestionService questionService;

    @GetMapping("/list")
    public String list(Model model) {
        List<Question> questionList = questionService.getList();
        model.addAttribute("questionList", questionList);
        return "question_list";
    }

    @GetMapping("/detail/{id}")
    public String detail(Model model, @PathVariable("id") Long id) {
        Question question = questionService.getQuestion(id);
        model.addAttribute("question", question);
        return "question_detail";
    }

    // 사용자가 "질문 등록하기" 버튼을 누르면 질문 등록 form이 화면에 표시
    //! 파라미터의 QuestionForm 객체를 지우면 th:object="${questionForm}를 resolving하지 못함!
    @GetMapping("/create")
    public String showQuestionForm(QuestionForm questionForm) {
        return "question_form";
    }

    // @Valid: Form 클래스의 필드값에 설정한 어노테이션 검증 기능이 동작
    @PostMapping("/create")
    public String createQuestion(@Valid QuestionForm questionForm, BindingResult bindingResult) {
        // 사용자가 제목, 내용 둘 다 입력하지 않았을 경우
        if ((questionForm.getSubject() == null || questionForm.getSubject().trim().isEmpty()) && (questionForm.getContent() == null || questionForm.getContent().trim().isEmpty())) {
            // Add a global error instead of field errors
            bindingResult.reject("bothFieldsEmpty", "제목과 내용을 입력해주세요.");
            log.error("사용자가 제목과 내용 모두 입력하지 않음");
            return "question_form";
        }

        if (bindingResult.hasErrors()) {
            log.error("폼 검증 오류: {}", bindingResult.getAllErrors());
            return "question_form";
        }
        questionService.create(questionForm.getSubject(), questionForm.getContent());
        return "redirect:/question/list"; // 질문 저장 후 질문목록으로 이동
    }
}
