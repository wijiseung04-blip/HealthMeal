package com.springboot.controller;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.web.ErrorResponse;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;

@Slf4j
@ControllerAdvice
public class GlobalControllerAdvice {

	// 모든 화면 헤더에서 로그인 여부를 쓰기 위해
	@ModelAttribute("loginEmail")
	public String loginEmail(Principal principal) {
		return principal == null ? null : principal.getName();
	}

	/** 업로드 용량 초과는 요청이 컨트롤러에 닿기 전에 나므로 여기서 안내한다 */
	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public String tooLarge(RedirectAttributes redirect) {
		redirect.addFlashAttribute("uploadError", "파일은 10MB 이하만 올릴 수 있습니다.");
		return "redirect:/mypage";
	}

	@ExceptionHandler(Exception.class)
	public String handle(Exception e, Model model, HttpServletResponse response) throws Exception {
		if (e instanceof ErrorResponse || e instanceof TypeMismatchException) {
			throw e; // 404·405·잘못된 요청 값(400) 등 표준 HTTP 오류는 스프링 기본 처리에 맡김
		}
		response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		log.error("처리되지 않은 예외", e);
		model.addAttribute("message", "요청을 처리하는 중 문제가 발생했습니다.");
		return "error/general";
	}
}
