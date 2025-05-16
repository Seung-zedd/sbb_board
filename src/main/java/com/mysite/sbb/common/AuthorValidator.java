package com.mysite.sbb.common;

import com.mysite.sbb.user.SiteUser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.function.Function;

@Component
public class AuthorValidator {
    // T 타입 파라미터는 getAuthor() 메서드가 있어야 함
    public <T> void validateAuthor(Principal principal, T entity, Function<T, SiteUser> authorExtractor) {
        SiteUser author = authorExtractor.apply(entity);
        if (!author.getUsername().equals(principal.getName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "수정 권한이 없습니다.");
        }
    }

}
