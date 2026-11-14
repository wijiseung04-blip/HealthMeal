package com.springboot.service;

import com.springboot.domain.Member;
import com.springboot.domain.Role;
import com.springboot.dto.SignupForm;
import com.springboot.exception.DuplicateUsernameException;
import com.springboot.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemberService {

	private final MemberRepository memberRepository;
	private final PasswordEncoder passwordEncoder;

	@Transactional
	public Long signup(SignupForm form) {
		if (memberRepository.existsByUsername(form.getUsername())) {
			throw new DuplicateUsernameException(form.getUsername());
		}
		Member member = new Member(form.getUsername(), passwordEncoder.encode(form.getPassword()), form.getName(), Role.USER);
		Long id = memberRepository.save(member).getId();
		log.info("회원 가입 완료: memberId={}", id);
		return id;
	}
}
