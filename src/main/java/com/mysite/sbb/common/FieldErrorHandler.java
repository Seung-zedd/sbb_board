package com.mysite.sbb.common;

import org.springframework.validation.BindingResult;

public interface FieldErrorHandler {
    String handleError(BindingResult bindingResult, String defaultView);
}
