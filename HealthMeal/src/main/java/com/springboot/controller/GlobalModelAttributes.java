package com.springboot.controller;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.security.Principal;

/** 모든 화면 헤더에서 로그인 여부를 쓰기 위한 공통 모델 속성 (예외 처리는 exception.CommonException) */
@ControllerAdvice
public class GlobalModelAttributes {

	@ModelAttribute("loginId")
	public String loginId(Principal principal) {
		return principal == null ? null : principal.getName();
	}
}
