package com.mysite.sbb.question;

import com.mysite.sbb.answer.AnswerForm;
import com.mysite.sbb.user.SiteUser;
import com.mysite.sbb.user.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;


@RequestMapping("/question")
@RequiredArgsConstructor
@Controller
@Slf4j
public class QuestionController {
    private final QuestionService questionService;
    private final UserService userService;

    @GetMapping("/list")
    public String list(Model model, @RequestParam(value = "page", defaultValue = "0") int page) {
        Page<Question> paging = questionService.getList(page);
        model.addAttribute("paging", paging);
        return "question_list";
    }

    @GetMapping("/detail/{id}")
    public String detail(Model model, @PathVariable("id") Long id, AnswerForm answerForm) {
        Question question = questionService.getQuestion(id);
        model.addAttribute("question", question);
        return "question_detail";
    }

    // 사용자가 "질문 등록하기" 버튼을 누르면 질문 등록 form이 화면에 표시
    //! 파라미터의 QuestionForm 객체를 지우면 th:object="${questionForm}를 resolving하지 못함!
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/create")
    public String showQuestionForm(QuestionForm questionForm) {
        return "question_form";
    }

    // @Valid: Form 클래스의 필드값에 설정한 어노테이션 검증 기능이 동작
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/create")
    public String createQuestion(@Valid QuestionForm questionForm, BindingResult bindingResult, Principal principal) {
        // 사용자가 제목, 내용 둘 다 입력하지 않았을 경우
        if (bindingResult.hasFieldErrors("subject") && bindingResult.hasFieldErrors("content")) {
            // Add a global error instead of field errors
            bindingResult.reject("bothFieldsEmpty", "제목과 내용을 입력해주세요.");
            log.error("사용자가 제목과 내용 모두 입력하지 않음: {}", bindingResult.getGlobalErrors());
            return "question_form";
        }

        if (bindingResult.hasErrors()) {
            log.error("폼 검증 오류: {}", bindingResult.getAllErrors());
            return "question_form";
        }
        SiteUser siteUser = userService.getUser(principal.getName());
        questionService.create(questionForm.getSubject(), questionForm.getContent(), siteUser);
        return "redirect:/question/list"; // 질문 저장 후 질문목록으로 이동
    }
}
