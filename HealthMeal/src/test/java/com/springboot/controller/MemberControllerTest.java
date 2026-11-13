package com.springboot.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MemberControllerTest {

	@Autowired MockMvc mvc;

	@Test
	void 비회원은_홈에_접근할_수_있다() throws Exception {
		mvc.perform(get("/")).andExpect(status().isOk());
	}

	@Test
	void 비회원이_회원_전용_페이지에_가면_로그인으로_보낸다() throws Exception {
		mvc.perform(get("/mypage"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/login"));
	}

	@Test
	@WithMockUser
	void 회원은_마이페이지에_접근할_수_있다() throws Exception {
		mvc.perform(get("/mypage")).andExpect(status().isOk());
	}

	@Test
	void 올바른_가입_요청은_로그인으로_리다이렉트된다() throws Exception {
		mvc.perform(post("/signup").with(csrf())
						.param("email", "new@test.com").param("name", "새회원")
						.param("password", "password123").param("passwordConfirm", "password123"))
				.andExpect(redirectedUrl("/login?signup"));
	}

	@Test
	void 비밀번호가_다르면_가입_폼으로_돌아온다() throws Exception {
		mvc.perform(post("/signup").with(csrf())
						.param("email", "x@test.com").param("name", "회원")
						.param("password", "password123").param("passwordConfirm", "different1"))
				.andExpect(status().isOk())
				.andExpect(view().name("signup"))
				.andExpect(model().attributeHasFieldErrors("signupForm", "passwordConfirm"));
	}

	@Test
	void 없는_주소는_404를_반환한다() throws Exception {
		mvc.perform(get("/no-such-page").with(user("u")))
				.andExpect(status().isNotFound());
	}
}
