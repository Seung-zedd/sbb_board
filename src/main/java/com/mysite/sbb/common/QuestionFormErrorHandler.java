package com.mysite.sbb.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.validation.BindingResult;

@Component
@Slf4j
public class QuestionFormErrorHandler implements FieldErrorHandler {
    @Override
    public String handleError(BindingResult bindingResult, String defaultView) {
        if (!bindingResult.hasErrors()) {
            return null; // 에러가 없으면 null 반환
        }

        if (bindingResult.hasFieldErrors("subject") && bindingResult.hasFieldErrors("content")) {
            bindingResult.reject("bothFieldsEmpty", "제목과 내용을 입력해주세요.");
            log.error("제목과 내용 모두 입력되지 않음: {}", bindingResult.getGlobalErrors());
        } else {
            log.error("폼 검증 오류 발생: {}", bindingResult.getAllErrors());
        }

        return defaultView; // 에러가 있으면 기본 뷰 반환
    }
}
