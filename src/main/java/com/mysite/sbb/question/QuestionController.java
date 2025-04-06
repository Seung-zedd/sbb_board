package com.mysite.sbb.question;

import com.mysite.sbb.answer.AnswerForm;
import com.mysite.sbb.user.SiteUser;
import com.mysite.sbb.user.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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
        String resultPage = handleError(bindingResult);
        if (resultPage != null) {
            return resultPage;
        }

        SiteUser siteUser = userService.getUser(principal.getName());
        questionService.create(questionForm.getSubject(), questionForm.getContent(), siteUser);
        log.info("Created question with siteUser: {}, subject: {}, content: {}", siteUser.getUsername(), questionForm.getSubject(), questionForm.getContent());
        return "redirect:/question/list"; // 질문 저장 후 질문목록으로 이동
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/modify/{id}")
    public String questionModify(QuestionForm questionForm, @PathVariable("id") Long id, Principal principal) {
        Question question = questionService.getQuestion(id);
        validateAuthor(principal, question);
        questionForm.setSubject(question.getSubject());
        questionForm.setContent(question.getContent());
        return "question_form";
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/modify/{id}")
    public String questionModify(@Valid QuestionForm questionForm, BindingResult bindingResult, Principal principal, @PathVariable("id") Long id) {
        String resultPage = handleError(bindingResult);
        if (resultPage != null) {
            return resultPage;
        }
        Question question = questionService.getQuestion(id);
        validateAuthor(principal, question);

        questionService.modify(question, questionForm.getSubject(), questionForm.getContent());
        log.info("Modified question with ID: {}, subject: {}, content: {}", question.getId(), question.getSubject(), question.getContent());
        return String.format("redirect:/question/detail/%s", id);
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/delete/{id}")
    public String questionDelete(Principal principal, @PathVariable("id") Long id) {
        Question question = questionService.getQuestion(id);
        validateAuthor(principal, question);

        log.info("Deleting question with ID: {}, subject: {}", question.getId(), question.getSubject());
        questionService.delete(question);
        return "redirect:/";
    }

    private void validateAuthor(Principal principal, Question question) {
        if (!question.getAuthor().getUsername().equals(principal.getName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "수정 권한이 없습니다.");
        }
    }

    private String handleError(BindingResult bindingResult) {
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

        return null;
    }
}
