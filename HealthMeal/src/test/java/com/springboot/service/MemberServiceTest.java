package com.springboot.service;

import com.springboot.domain.Member;
import com.springboot.dto.SignupForm;
import com.springboot.exception.DuplicateUsernameException;
import com.springboot.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class MemberServiceTest {

	@Autowired MemberService memberService;
	@Autowired MemberRepository memberRepository;
	@Autowired PasswordEncoder passwordEncoder;

	@Test
	void 가입하면_비밀번호가_암호화되어_저장된다() {
		Long id = memberService.signup(form("auser"));

		Member saved = memberRepository.findById(id).orElseThrow();
		assertThat(saved.getPassword()).isNotEqualTo("password123");
		assertThat(passwordEncoder.matches("password123", saved.getPassword())).isTrue();
	}

	@Test
	void 같은_아이디로_두번_가입할_수_없다() {
		memberService.signup(form("dupuser"));

		assertThatThrownBy(() -> memberService.signup(form("dupuser")))
				.isInstanceOf(DuplicateUsernameException.class);
	}

	private SignupForm form(String username) {
		SignupForm f = new SignupForm();
		f.setUsername(username);
		f.setName("테스터");
		f.setPassword("password123");
		f.setPasswordConfirm("password123");
		return f;
	}
}
