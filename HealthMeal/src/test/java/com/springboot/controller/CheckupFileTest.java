package com.springboot.controller;

import com.springboot.dto.SignupForm;
import com.springboot.repository.CheckupFileRepository;
import com.springboot.service.MemberService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class CheckupFileTest {

	@Autowired MockMvc mvc;
	@Autowired MemberService memberService;
	@Autowired CheckupFileRepository fileRepository;

	private static final byte[] PDF = "%PDF-1.4\n% 검진 결과 테스트\n%%EOF".getBytes(StandardCharsets.UTF_8);

	private String newMember() {
		SignupForm f = new SignupForm();
		f.setEmail(UUID.randomUUID() + "@test.com");
		f.setName("회원");
		f.setPassword("password123");
		f.setPasswordConfirm("password123");
		memberService.signup(f);
		return f.getEmail();
	}

	private MockMultipartFile pdf(String name, byte[] content) {
		return new MockMultipartFile("file", name, "application/pdf", content);
	}

	@Test
	void 비회원은_업로드할_수_없다() throws Exception {
		mvc.perform(multipart("/mypage/checkups").file(pdf("a.pdf", PDF)).with(csrf()))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/login"));
	}

	@Test
	void 회원은_PDF를_올리고_내려받는다() throws Exception {
		String email = newMember();
		mvc.perform(multipart("/mypage/checkups").file(pdf("2026 검진결과.pdf", PDF)).with(csrf()).with(user(email)))
				.andExpect(redirectedUrl("/mypage"))
				.andExpect(flash().attribute("message", "건강검진 결과를 올렸습니다."));

		var saved = fileRepository.findByMemberEmailOrderByUploadedAtDesc(email);
		assertThat(saved).hasSize(1);
		Long id = saved.getFirst().getId();

		mvc.perform(get("/mypage").with(user(email)))
				.andExpect(content().string(containsString("2026 검진결과.pdf")));
		mvc.perform(get("/mypage/checkups/" + id + "/file").with(user(email)))
				.andExpect(status().isOk())
				.andExpect(content().contentType("application/pdf"))
				.andExpect(header().string("Content-Disposition", containsString("attachment")))
				.andExpect(content().bytes(PDF));
	}

	@Test
	void PDF가_아닌_파일은_거부한다() throws Exception {
		String email = newMember();
		mvc.perform(multipart("/mypage/checkups").file(pdf("fake.pdf", "hello".getBytes())).with(csrf()).with(user(email)))
				.andExpect(redirectedUrl("/mypage"))
				.andExpect(flash().attribute("uploadError", "PDF 형식이 아닌 파일입니다."));
		mvc.perform(multipart("/mypage/checkups").file(pdf("note.txt", PDF)).with(csrf()).with(user(email)))
				.andExpect(flash().attribute("uploadError", "PDF 파일만 올릴 수 있습니다."));
		assertThat(fileRepository.findByMemberEmailOrderByUploadedAtDesc(email)).isEmpty();
	}

	@Test
	void 다른_회원의_파일은_내려받거나_삭제할_수_없다() throws Exception {
		String owner = newMember();
		String other = newMember();
		mvc.perform(multipart("/mypage/checkups").file(pdf("a.pdf", PDF)).with(csrf()).with(user(owner)));
		Long id = fileRepository.findByMemberEmailOrderByUploadedAtDesc(owner).getFirst().getId();

		mvc.perform(get("/mypage/checkups/" + id + "/file").with(user(other))).andExpect(status().isNotFound());
		mvc.perform(post("/mypage/checkups/" + id + "/delete").with(csrf()).with(user(other)))
				.andExpect(flash().attribute("message", "파일을 찾을 수 없습니다."));
		assertThat(fileRepository.findById(id)).isPresent();

		mvc.perform(post("/mypage/checkups/" + id + "/delete").with(csrf()).with(user(owner)))
				.andExpect(flash().attribute("message", "파일을 삭제했습니다."));
		assertThat(fileRepository.findById(id)).isEmpty();
		mvc.perform(get("/mypage/checkups/" + id + "/file").with(user(owner))).andExpect(status().isNotFound());
	}
}
