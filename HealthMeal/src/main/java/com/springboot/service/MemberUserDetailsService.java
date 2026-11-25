package com.springboot.service;

import com.springboot.domain.Member;
import com.springboot.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** 로그인할 때 스프링 시큐리티에 회원 정보(아이디·암호화된 비밀번호·권한)를 넘긴다 */
@Service
@RequiredArgsConstructor
public class MemberUserDetailsService implements UserDetailsService {

	private final MemberRepository memberRepository;

	@Override
	public UserDetails loadUserByUsername(String username) {
		Member member = memberRepository.findByUsername(username)
				.orElseThrow(() -> new UsernameNotFoundException(username));
		return User.withUsername(member.getUsername())
				.password(member.getPassword())
				.roles(member.getRole().name())
				.build();
	}
}
