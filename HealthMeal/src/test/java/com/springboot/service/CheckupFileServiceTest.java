package com.springboot.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CheckupFileServiceTest {

	@Test
	void 파일_이름의_경로와_제어문자를_지운다() {
		assertThat(CheckupFileService.cleanName("../../etc/passwd.pdf")).isEqualTo("passwd.pdf");
		assertThat(CheckupFileService.cleanName("C:\\Users\\me\\검진.pdf")).isEqualTo("검진.pdf");
		assertThat(CheckupFileService.cleanName(null)).isEqualTo("checkup.pdf");
	}
}
