package com.mysite.sbb.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class CustomAccessDeniedHandler implements AccessDeniedHandler {
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException) throws IOException {
        // 세션에 에러 메시지 저장
        request.getSession().setAttribute("userRoleError", "현재 관리자만 글을 작성할 수 있습니다.");
        // 질문 목록 페이지로 리다이렉트
        response.sendRedirect("/question/list");
    }
}
