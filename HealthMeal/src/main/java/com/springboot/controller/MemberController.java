package com.springboot.controller;

import com.springboot.dto.SignupForm;
import com.springboot.exception.DuplicateUsernameException;
import com.springboot.service.MemberService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.WebDataBinder;
import com.springboot.validator.PasswordConfirmValidator;

@Controller
@RequiredArgsConstructor
public class MemberController {

	private final MemberService memberService;
	private final PasswordConfirmValidator passwordConfirmValidator;

	@GetMapping("/login")
	public String loginForm() {
		return "login";
	}

	@GetMapping("/signup")
	public String signupForm(Model model) {
		model.addAttribute("signupForm", new SignupForm());
		return "signup";
	}

	/**
	 * 6장 @InitBinder: 폼에서 받을 필드만 바인딩하고(그 밖의 파라미터는 무시),
	 * 9장 Validator 인터페이스로 비밀번호 확인 검사를 추가한다 (Bean Validation 애너테이션 검사와 함께 실행)
	 */
	@InitBinder("signupForm")
	public void initBinder(WebDataBinder binder) {
		binder.setAllowedFields("username", "password", "passwordConfirm", "name");
		binder.addValidators(passwordConfirmValidator);
	}

	@PostMapping("/signup")
	public String signup(@Valid @ModelAttribute SignupForm signupForm, BindingResult bindingResult) {
		if (bindingResult.hasErrors()) {
			return "signup";
		}
		try {
			memberService.signup(signupForm);
		} catch (DuplicateUsernameException e) {
			// 검사와 저장 사이에 같은 아이디가 먼저 가입된 경우
			bindingResult.rejectValue("username", "signup.username.duplicate");
			return "signup";
		}
		return "redirect:/login?signup";
	}
}
