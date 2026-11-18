package com.springboot.validator;

import com.springboot.repository.MemberRepository;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.beans.factory.annotation.Autowired;

/** 9장 ConstraintValidator 구현체: 회원 저장소에서 같은 아이디가 있는지 확인한다 */
public class UniqueUsernameValidator implements ConstraintValidator<UniqueUsername, String> {

	@Autowired
	private MemberRepository memberRepository;

	@Override
	public boolean isValid(String username, ConstraintValidatorContext context) {
		// 빈 값·형식 오류는 @NotBlank·@Pattern이 알린다. 저장소가 없는 환경(단위 검증)에서는 건너뛴다
		if (username == null || username.isBlank() || memberRepository == null) return true;
		return !memberRepository.existsByUsername(username);
	}
}
