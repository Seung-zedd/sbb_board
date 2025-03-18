package com.mysite.sbb;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {
    @GetMapping("/hello")
    public String hello() {
        return "Hello World SBB";
    }

    @GetMapping("/jump")
    public String jump() {
        return "점프 투 스프링 부트";
    }
}
