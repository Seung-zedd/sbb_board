package com.mysite.sbb;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class MainController {
    @GetMapping("/sbb")
    public ResponseEntity<String> index() {
        return ResponseEntity.ok("안녕하세요 sbb에 오신 것을 환영합니다.");
    }

    @GetMapping("/")
    public String root() {
        return "redirect:/question/list";
    }
}
