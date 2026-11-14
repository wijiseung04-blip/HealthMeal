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

@Controller
@RequiredArgsConstructor
public class MemberController {

	private final MemberService memberService;

	@GetMapping("/login")
	public String loginForm() {
		return "login";
	}

	@GetMapping("/signup")
	public String signupForm(Model model) {
		model.addAttribute("signupForm", new SignupForm());
		return "signup";
	}

	@PostMapping("/signup")
	public String signup(@Valid @ModelAttribute SignupForm signupForm, BindingResult bindingResult) {
		if (!bindingResult.hasFieldErrors("password")
				&& !signupForm.getPassword().equals(signupForm.getPasswordConfirm())) {
			bindingResult.rejectValue("passwordConfirm", "mismatch", "비밀번호가 일치하지 않습니다.");
		}
		if (bindingResult.hasErrors()) {
			return "signup";
		}
		try {
			memberService.signup(signupForm);
		} catch (DuplicateUsernameException e) {
			bindingResult.rejectValue("username", "duplicate", "이미 사용 중인 아이디입니다.");
			return "signup";
		}
		return "redirect:/login?signup";
	}
}
