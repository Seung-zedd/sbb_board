package com.mysite.sbb.user;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequiredArgsConstructor
@RequestMapping("/user")
@Slf4j
public class UserController {
    private final UserService userService;

    @GetMapping("/signup")
    public String showSignup(UserCreateForm userCreateForm) {
        return "signup_form";
    }

    @PostMapping("/signup")
    public String signup(@Valid UserCreateForm userCreateForm, BindingResult bindingResult) {
        // 사용자가 폼 내용을 다 입력하지 않았을 경우
        if (bindingResult.hasFieldErrors("username") && bindingResult.hasFieldErrors("password1") && bindingResult.hasFieldErrors("password2") && bindingResult.hasFieldErrors("email")) {
            bindingResult.reject("AllFieldsEmpty", "ID, 패스워드, 이메일을 모두 입력해주세요.");
            log.error("사용자가 회원 가입 폼을 모두 입력하지 않음: {}", bindingResult.getGlobalErrors());
            return "signup_form";
        }

        if (bindingResult.hasErrors()) {
            log.error("폼 검증 오류: {}", bindingResult.getAllErrors());
            return "signup_form";
        }

        // 비밀번호와 비밀번호 확인이 서로 일치하지 않은 경우
        if (!userCreateForm.getPassword1().equals(userCreateForm.getPassword2())) {
            bindingResult.rejectValue("password2", "passwordIncorrect", "2개의 패스워드가 일치하지 않습니다");
            return "signup_form";
        }

        try {
            userService.create(userCreateForm.getUsername(), userCreateForm.getEmail(), userCreateForm.getPassword1());

        } catch (DataIntegrityViolationException e) {
            bindingResult.reject("signupFailed", "이미 등록된 사용자입니다.");
            log.error("사용자가 중복 회원 가입을 시도: {}", bindingResult.getAllErrors());
            return "signup_form";
        } catch (Exception e) {
            bindingResult.reject("signupFailed", e.getMessage());
            log.error("그 밖의 다른 예외: {}", bindingResult.getAllErrors());
            return "signup_form";
        }
        return "redirect:/";
    }

    @GetMapping("/login")
    public String login() {
        return "login_form";
    }

}
