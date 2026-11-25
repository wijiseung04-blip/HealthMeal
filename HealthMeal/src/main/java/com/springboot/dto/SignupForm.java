package com.springboot.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.springboot.validator.UniqueUsername;
import lombok.Getter;
import lombok.Setter;

/** 회원가입 폼. 9장 제약사항 애너테이션(@UniqueUsername 등)과 메시지 리소스 키로 검증한다 */
@Getter
@Setter
public class SignupForm {

	@NotBlank(message = "{signup.username.notBlank}")
	@Pattern(regexp = "^[a-z0-9_]{4,20}$", message = "{signup.username.pattern}")
	@UniqueUsername // 9장 사용자 정의 애너테이션: 이미 가입된 아이디인지
	private String username;

	@NotBlank(message = "{signup.password.notBlank}")
	@Size(min = 8, max = 64, message = "{signup.password.size}")
	private String password;

	@NotBlank(message = "{signup.passwordConfirm.notBlank}")
	private String passwordConfirm;

	@NotBlank(message = "{signup.name.notBlank}")
	@Size(max = 50, message = "{signup.name.size}")
	private String name;
}
