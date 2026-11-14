package com.springboot.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SignupForm {

	@NotBlank(message = "아이디를 입력하세요.")
	@Pattern(regexp = "^[a-z0-9_]{4,20}$", message = "아이디는 영문 소문자·숫자·밑줄(_) 4~20자여야 합니다.")
	private String username;

	@NotBlank(message = "비밀번호를 입력하세요.")
	@Size(min = 8, max = 64, message = "비밀번호는 8~64자여야 합니다.")
	private String password;

	@NotBlank(message = "비밀번호 확인을 입력하세요.")
	private String passwordConfirm;

	@NotBlank(message = "이름을 입력하세요.")
	@Size(max = 50, message = "이름은 50자 이하여야 합니다.")
	private String name;
}
