package com.mysite.sbb.question;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RequestMapping("/question")
@RequiredArgsConstructor
@Controller
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

    @GetMapping("/create")
    // 사용자가 "질문 등록하기" 버튼을 누르면 질문 등록 form이 화면에 표시
    public String showQuestionForm() {
        return "question_form";
    }

    @PostMapping("/create")
    public String createQuestion(@RequestParam("subject") String subject, @RequestParam("content") String content) {
        questionService.create(subject, content);
        return "redirect:/question/list"; // 질문 저장 후 질문목록으로 이동
    }
}
