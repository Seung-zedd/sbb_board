package com.mysite.sbb.question;

import com.mysite.sbb.answer.AnswerForm;
import com.mysite.sbb.common.AuthorValidator;
import com.mysite.sbb.common.FieldErrorHandler;
import com.mysite.sbb.question.dto.QuestionDetailDto;
import com.mysite.sbb.question.dto.QuestionListItemDto;
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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;


@RequestMapping("/question")
@RequiredArgsConstructor
@Controller
@Slf4j
public class QuestionController {
    private final QuestionService questionService;
    private final UserService userService;
    private final FieldErrorHandler fieldErrorHandler;
    private final AuthorValidator authorValidator;

    @GetMapping("/list")
    public String list(Model model, @RequestParam(value = "page", defaultValue = "0") int page, @RequestParam(value = "kw", defaultValue = "") String kw) {
        Page<QuestionListItemDto> paging = questionService.getList(page, kw);
        model.addAttribute("paging", paging);
        model.addAttribute("kw", kw);
        return "question_list";
    }

    @GetMapping("/detail/{id}")
    public String detail(Model model, @PathVariable("id") Long id, AnswerForm answerForm) {
        QuestionDetailDto questionDto = questionService.getQuestionDto(id);
        model.addAttribute("questionDto", questionDto);
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
        // 폼 검증
        String resultPage = fieldErrorHandler.handleError(bindingResult, "question_form");
        if (resultPage != null) {
            return resultPage;
        }

        // 서비스 로직 실행
        SiteUser siteUser = userService.getUser(principal.getName());
        questionService.create(questionForm.getSubject(), questionForm.getContent(), siteUser);
        log.info("created question with siteUser: {}, subject: {}, content: {}", siteUser.getUsername(), questionForm.getSubject(), questionForm.getContent());
        return "redirect:/question/list"; // 질문 저장 후 질문목록으로 이동

    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/modify/{id}")
    public String modifyQuestion(QuestionForm questionForm, @PathVariable("id") Long id, Principal principal) {
        Question question = questionService.getQuestion(id);
        authorValidator.validateAuthor(principal, question, Question::getAuthor);

        questionForm.setSubject(question.getSubject());
        questionForm.setContent(question.getContent());
        return "question_form";
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/modify/{id}")
    public String modifyQuestion(@Valid QuestionForm questionForm, BindingResult bindingResult, Principal principal, @PathVariable("id") Long id) {
        // 폼 검증
        String resultPage = fieldErrorHandler.handleError(bindingResult, "question_form");
        if (resultPage != null) {
            return resultPage;
        }
        // 서비스 로직 실행
        Question question = questionService.getQuestion(id);
        authorValidator.validateAuthor(principal, question, Question::getAuthor);
        questionService.modify(question, questionForm.getSubject(), questionForm.getContent());
        QuestionDetailDto questionDto = QuestionDetailDto.from(question);
        log.info("modified question with ID: {}, subject: {}, content: {}, modifyDate: {}", questionDto.getId(), questionDto.getSubject(), questionDto.getContent(), questionDto.getModifyDate());
        return String.format("redirect:/question/detail/%s", id);
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/delete/{id}")
    public String deleteQuestion(Principal principal, @PathVariable("id") Long id) {
        Question question = questionService.getQuestion(id);
        authorValidator.validateAuthor(principal, question, Question::getAuthor);
        questionService.delete(question);
        log.info("after deleting question with ID: {}, subject: {}", question.getId(), question.getSubject());
        return "redirect:/";
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/vote/{id}")
    public String voteQuestion(RedirectAttributes redirectAttributes, Principal principal, @PathVariable("id") Long id) {
        Question question = questionService.getQuestion(id);
        SiteUser siteUser = userService.getUser(principal.getName());

        try {
            questionService.vote(question, siteUser);
            log.info("after voting question with ID: {}, siteUser: {}", question.getId(), siteUser.getId());
        } catch (IllegalStateException e) {
            redirectAttributes.addFlashAttribute("voteError", e.getMessage());
            log.error("duplicate vote error for question ID: {}, siteUser: {}", question.getId(), siteUser.getId());
        }

        return String.format("redirect:/question/detail/%s", id);
    }



    /*private void validateAuthor(Principal principal, Question question) {

        if (!question.getAuthor().getUsername().equals(principal.getName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "수정 권한이 없습니다.");
        }
    }*/

}
