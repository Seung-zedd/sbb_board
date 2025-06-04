package com.mysite.sbb.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserCreateForm {
    @Size(min = 3, max = 25, message = "사용자 ID는 3자 이상 25자 이하로 입력해야 합니다.")
    @NotBlank(message = "사용자ID는 필수항목입니다.")
    private String username;

    @NotBlank(message = "비밀번호는 필수항목입니다.")
    @Size(min = 8, max = 20, message = "사용자 비밀번호는 대문자, 숫자, 특수문자를 포함해 8자 이상 20자 이하로 입력해야 합니다.")
    @Pattern(
            regexp = "^(?=.*[A-Z])(?=.*\\d)(?=.*[!@#$%^&*()\\-_=+\\\\|\\[\\]{};:'\",.<>/?]).{8,}$",
            message = "비밀번호는 대문자, 숫자, 특수문자를 포함해 8자 이상 20자 이하이어야 합니다."
    )
    private String password1;

    @NotBlank(message = "비밀번호 확인은 필수항목입니다.")
    private String password2;

    @NotBlank(message = "이메일은 필수항목입니다.")
    @Pattern(
            regexp = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.com$",
            message = ".com으로 끝나는 이메일만 허용됩니다."
    )
    private String email;

}
