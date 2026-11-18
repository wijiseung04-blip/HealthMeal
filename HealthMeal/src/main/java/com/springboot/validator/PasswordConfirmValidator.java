package com.springboot.validator;

import com.springboot.dto.SignupForm;
import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;

/** 9장 Validator 인터페이스: 두 필드를 함께 보는 검사 (비밀번호와 비밀번호 확인이 같은지). @InitBinder로 등록 */
@Component
public class PasswordConfirmValidator implements Validator {

	@Override
	public boolean supports(Class<?> clazz) {
		return SignupForm.class.isAssignableFrom(clazz);
	}

	@Override
	public void validate(Object target, Errors errors) {
		SignupForm form = (SignupForm) target;
		if (errors.hasFieldErrors("password") || errors.hasFieldErrors("passwordConfirm")) return;
		if (form.getPassword() != null && !form.getPassword().equals(form.getPasswordConfirm())) {
			errors.rejectValue("passwordConfirm", "signup.passwordConfirm.mismatch");
		}
	}
}
