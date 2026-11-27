package com.springboot.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 스프링 시큐리티 설정: 비회원 허용 경로(홈·가입·로그인·정적 파일·레시피·식사 조합·영양 퀴즈), 폼 로그인/로그아웃, CSRF.
 * 데이터 적재 프로그램(웹 서버 없이 실행)에서는 쓰지 않는다
 */
@ConditionalOnWebApplication
@Configuration
public class SecurityConfig {

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		http
				.authorizeHttpRequests(auth -> auth
						// 비회원 접근 가능 (정적 파일: css·js·디자인 이미지)
						.requestMatchers("/", "/signup", "/login", "/css/**", "/js/**", "/images/**", "/error").permitAll()
						.requestMatchers(HttpMethod.GET, "/recipes/**", "/api/recipes/**", "/meals", "/game", "/api/game/**").permitAll()
						.requestMatchers("/admin/**").hasRole("ADMIN")
						// 그 외는 회원 전용
						.anyRequest().authenticated())
				.formLogin(form -> form
						.loginPage("/login")
						.usernameParameter("username")
						.defaultSuccessUrl("/", true)
						.failureUrl("/login?error")
						.permitAll())
				.logout(logout -> logout.logoutSuccessUrl("/login?logout")); // 10장 로그아웃 후 로그인 화면에 안내
		return http.build();
	}
}
