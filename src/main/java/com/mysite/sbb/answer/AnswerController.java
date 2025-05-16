package com.mysite.sbb.answer;

import com.mysite.sbb.common.AuthorValidator;
import com.mysite.sbb.question.Question;
import com.mysite.sbb.question.QuestionService;
import com.mysite.sbb.user.SiteUser;
import com.mysite.sbb.user.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;

@RequestMapping("/answer")
@Controller
@RequiredArgsConstructor
@Slf4j
public class AnswerController {
    private final QuestionService questionService;
    private final AnswerService answerService;
    private final UserService userService;
    private final AuthorValidator authorValidator;

    // 답변에 글쓴이 항목을 추가하기 위해 스프링 시큐리티의 Principal 객체 사용
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/create/{id}")
    public String createAnswer(Model model, @PathVariable("id") Long id, @Valid AnswerForm answerForm, BindingResult bindingResult, Principal principal) {
        Question question = questionService.getQuestion(id);
        SiteUser siteUser = userService.getUser(principal.getName());
        // 사용자가 내용을 입력하지 않으면 질문 detail로 리다이렉트시킴
        if (bindingResult.hasErrors()) {
            model.addAttribute("question", question);
            return "question_detail";
        }

        Answer answer = answerService.create(question, answerForm.getContent(), siteUser);
        log.info("created answer with siteUser: {}, content: {}", siteUser.getUsername(), answerForm.getContent());
        return String.format("redirect:/question/detail/%s#answer_%s", id, answer.getId());
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/modify/{id}")
    public String modifyAnswer(AnswerForm answerForm, @PathVariable("id") Long id, Principal principal) {
        Answer answer = answerService.getAnswer(id);
        authorValidator.validateAuthor(principal, answer, Answer::getAuthor);
        answerForm.setContent(answer.getContent());
        return "answer_form";
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/modify/{id}")
    public String modifyAnswer(@Valid AnswerForm answerForm, BindingResult bindingResult, @PathVariable("id") Long id, Principal principal) {
        if (bindingResult.hasErrors()) {
            log.error("폼 검증 오류: {}", bindingResult.getAllErrors());
            return "answer_form";
        }
        Answer answer = answerService.getAnswer(id);
        authorValidator.validateAuthor(principal, answer, Answer::getAuthor);
        answerService.modify(answer, answerForm.getContent());
        log.info("modified answer with ID: {}, content: {}, modifyDate: {}", answer.getId(), answer.getContent(), answer.getModifyDate());
        return String.format("redirect:/question/detail/%s#answer_%s", answer.getQuestion().getId(), answer.getId());
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/delete/{id}")
    public String deleteAnswer(Principal principal, @PathVariable("id") Long id) {
        Answer answer = answerService.getAnswer(id);
        authorValidator.validateAuthor(principal, answer, Answer::getAuthor);

        log.info("deleting answer with ID: {}, content: {}", answer.getId(), answer.getContent());
        answerService.delete(answer);
        log.info("after deleting answer with ID: {}, content: {}, question ID: {}", answer.getId(), answer.getContent(), answer.getQuestion().getId());
        return String.format("redirect:/question/detail/%s", answer.getQuestion().getId());
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/vote/{id}")
    public String voteAnswer(RedirectAttributes redirectAttributes, Principal principal, @PathVariable("id") Long id) {
        Answer answer = answerService.getAnswer(id);
        SiteUser siteUser = userService.getUser(principal.getName());

        try {
            answerService.vote(answer, siteUser);
            log.info("after voting answer with ID: {}, siteUser:{}", answer.getId(), siteUser.getId());
        } catch (IllegalStateException e) {
            redirectAttributes.addFlashAttribute("voteError", e.getMessage());
            log.error("duplicate vote error for answer ID: {}, siteUser: {}", answer.getId(), siteUser.getId());
        }

        return String.format("redirect:/question/detail/%s#answer_%s", answer.getQuestion().getId(), answer.getId());
    }


    /*private void validateAuthor(Principal principal, Answer answer) {
        if (!answer.getAuthor().getUsername().equals(principal.getName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "수정 권한이 없습니다.");
        }
    }*/
}
