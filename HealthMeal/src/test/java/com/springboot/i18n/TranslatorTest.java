package com.springboot.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class TranslatorTest {

	private final Translator tr = new Translator();

	@AfterEach
	void reset() {
		LocaleContextHolder.resetLocaleContext();
	}

	@Test
	void 한국어_화면에서는_그대로() {
		LocaleContextHolder.setLocale(Locale.KOREA);
		assertThat(tr.t("30% 감량")).isEqualTo("30% 감량");
		assertThat(tr.d("나트륨")).isEqualTo("나트륨");
	}

	@Test
	void 영어_화면에서는_문장_틀과_사전으로_번역한다() {
		LocaleContextHolder.setLocale(Locale.ENGLISH);
		assertThat(tr.t("나트륨 520 mg — 한 끼 기준 767 mg 이하 (68%)")).isEqualTo("Sodium 520 mg — within the 767 mg meal limit (68%)");
		assertThat(tr.t("30% 감량")).isEqualTo("Reduce by 30%");
		assertThat(tr.t("간장 2g → 50% 감량")).endsWith("→ Reduce by 50%");
		assertThat(tr.t("공복혈당이 기준 이상이라 혈당을 빨리 올리는 당류를 열량의 10% 이하로 (프로필이 없어 하루 2,000kcal 기준)"))
				.isEqualTo("Fasting glucose is above the reference, so sugars that raise blood glucose quickly ≤ 10% of energy (based on 2,000 kcal/day, no profile)");
		assertThat(tr.t("기름기가 적어 퍽퍽해질 수 있음 / AI 교차검토 규칙(사람 확인 전)"))
				.isEqualTo("Leaner, may be drier / AI cross-checked rule (not yet confirmed by a person)");
		assertThat(tr.t("공복혈당 값은 40~600 mg/dL 사이여야 합니다.")).isEqualTo("Fasting glucose must be between 40~600 mg/dL.");
		assertThat(tr.d("총 포화 지방산")).isEqualTo("Saturated fat");
		assertThat(tr.d("국&찌개")).isEqualTo("Soup & stew");
		assertThat(tr.t("모르는 문장")).isEqualTo("모르는 문장");
	}
}
