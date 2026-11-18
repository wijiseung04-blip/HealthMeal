package com.springboot.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 11장 @ControllerAdvice 전역 예외 처리: 컨트롤러에서 처리하지 않은 예외를 공통 오류 페이지(error/common)로.
 * @ResponseStatus가 붙은 예외(404 등)와 스프링 표준 HTTP 오류는 각자의 상태 코드 처리에 맡긴다
 */
@Slf4j
@ControllerAdvice
public class CommonException {

	/** 업로드 용량 초과는 요청이 컨트롤러에 닿기 전에 나므로 여기서 안내한다 */
	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public String tooLarge(RedirectAttributes redirect) {
		redirect.addFlashAttribute("uploadError", "파일은 10MB 이하만 올릴 수 있습니다.");
		return "redirect:/mypage";
	}

	@ExceptionHandler(Exception.class)
	public ModelAndView handleErrorCommon(Exception e, HttpServletRequest request, HttpServletResponse response) throws Exception {
		if (e instanceof ErrorResponse || e instanceof TypeMismatchException
				|| AnnotatedElementUtils.hasAnnotation(e.getClass(), ResponseStatus.class)) {
			throw e; // 404·405·400과 @ResponseStatus 예외는 스프링 기본 처리(상태 코드 + error/404 등)에 맡김
		}
		response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		log.error("처리되지 않은 예외: {} {}", request.getMethod(), request.getRequestURI(), e);
		ModelAndView modelAndView = new ModelAndView("error/common");
		modelAndView.addObject("exception", e.getClass().getSimpleName()); // 화면에는 예외 종류만 (내부 메시지는 로그에만)
		return modelAndView;
	}
}
