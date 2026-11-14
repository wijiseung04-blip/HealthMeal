package com.springboot.controller;

import com.springboot.dto.SignupForm;
import com.springboot.repository.CheckupFileRepository;
import com.springboot.repository.HealthCheckupRepository;
import com.springboot.service.MemberService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
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
class HealthCheckupFlowTest {

	@Autowired MockMvc mvc;
	@Autowired MemberService memberService;
	@Autowired CheckupFileRepository fileRepository;
	@Autowired HealthCheckupRepository checkupRepository;
	@Autowired JdbcTemplate jdbc;

	private String newMember() {
		SignupForm f = new SignupForm();
		f.setUsername("u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
		f.setName("회원");
		f.setPassword("password123");
		f.setPasswordConfirm("password123");
		memberService.signup(f);
		return f.getUsername();
	}

	private Long upload(String username) throws Exception {
		byte[] pdf = Files.readAllBytes(Path.of("src/test/resources/checkup-sample.pdf"));
		mvc.perform(multipart("/mypage/checkups").file(new MockMultipartFile("file", "검진.pdf", "application/pdf", pdf)).with(csrf()).with(user(username)));
		return fileRepository.findByMemberUsernameOrderByUploadedAtDesc(username).getFirst().getId();
	}

	@Test
	void PDF에서_추출하고_확인한_수치를_암호화해_저장하고_목표를_추천한다() throws Exception {
		String username = newMember();
		Long fileId = upload(username);

		// 추출 결과가 폼에 채워진다
		mvc.perform(get("/mypage/health/new").param("fileId", fileId.toString()).with(user(username)))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("13개 항목을 찾았습니다")))
				.andExpect(content().string(containsString("value=\"138\"")))
				.andExpect(content().string(containsString("value=\"2026-10-15\"")));

		// 사용자가 혈당 하나를 고쳐서 저장
		var result = mvc.perform(post("/mypage/health").with(csrf()).with(user(username))
						.param("fileId", fileId.toString()).param("sex", "M")
						.param("values[SYSTOLIC_BP]", "138").param("extracted[SYSTOLIC_BP]", "138")
						.param("values[DIASTOLIC_BP]", "88").param("extracted[DIASTOLIC_BP]", "88")
						.param("values[FASTING_GLUCOSE]", "106").param("extracted[FASTING_GLUCOSE]", "104")
						.param("values[TRIGLYCERIDE]", "178").param("extracted[TRIGLYCERIDE]", "178"))
				.andExpect(status().is3xxRedirection())
				.andReturn();
		String location = result.getResponse().getRedirectedUrl();

		mvc.perform(get(location).with(user(username)))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("저나트륨")))
				.andExpect(content().string(containsString("고혈압 전단계")))
				.andExpect(content().string(containsString("추출 후 수정")))
				.andExpect(content().string(containsString("/recipes?goal=LOW_SODIUM")));

		// DB에는 암호문만 있다
		var stored = jdbc.queryForList("SELECT value_encrypted FROM checkup_values", String.class);
		assertThat(stored).isNotEmpty().allSatisfy(v -> assertThat(v).startsWith("v1:").doesNotContain("138"));
	}

	@Test
	void 잘못된_값은_저장하지_않고_다시_입력받는다() throws Exception {
		String username = newMember();
		long before = checkupRepository.count();
		mvc.perform(post("/mypage/health").with(csrf()).with(user(username)).param("sex", "M").param("values[FASTING_GLUCOSE]", "abc"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("숫자로 입력하세요")));
		mvc.perform(post("/mypage/health").with(csrf()).with(user(username)).param("sex", "M").param("values[SYSTOLIC_BP]", "999"))
				.andExpect(content().string(containsString("60~260")));
		mvc.perform(post("/mypage/health").with(csrf()).with(user(username)).param("sex", "M"))
				.andExpect(content().string(containsString("하나 이상")));
		assertThat(checkupRepository.count()).isEqualTo(before);
	}

	@Test
	void 다른_회원의_검진_결과와_파일은_볼_수_없다() throws Exception {
		String owner = newMember();
		String other = newMember();
		Long fileId = upload(owner);
		String location = mvc.perform(post("/mypage/health").with(csrf()).with(user(owner)).param("sex", "F").param("values[BMI]", "22"))
				.andReturn().getResponse().getRedirectedUrl();

		mvc.perform(get(location).with(user(other))).andExpect(status().isNotFound());
		mvc.perform(get("/mypage/health/new").param("fileId", fileId.toString()).with(user(other))).andExpect(status().isNotFound());
		mvc.perform(get(location)).andExpect(status().is3xxRedirection()); // 비회원 → 로그인
	}

	@Test
	void PDF를_지워도_확인한_수치는_남는다() throws Exception {
		String username = newMember();
		Long fileId = upload(username);
		String location = mvc.perform(post("/mypage/health").with(csrf()).with(user(username)).param("fileId", fileId.toString())
				.param("sex", "M").param("values[BMI]", "26.7").param("extracted[BMI]", "26.7")).andReturn().getResponse().getRedirectedUrl();

		mvc.perform(post("/mypage/checkups/" + fileId + "/delete").with(csrf()).with(user(username)));
		mvc.perform(get(location).with(user(username)))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("저칼로리")));
	}
}
