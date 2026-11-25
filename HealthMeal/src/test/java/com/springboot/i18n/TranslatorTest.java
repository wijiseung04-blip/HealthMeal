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

	@Test
	void 괄호_단위가_붙은_재료_줄과_식품_이름_조각을_번역한다() {
		LocaleContextHolder.setLocale(Locale.ENGLISH);
		tr.putForTest("방울토마토", "Cherry tomato");
		tr.putForTest("물", "Water");
		tr.putForTest("부추", "Chives");
		tr.putForTest("다진 마늘", "Minced garlic");
		tr.putForTest("양파", "Onion");
		tr.putForTest("가상토마토", "Mock tomato");
		tr.putForTest("가상방울", "Mock cherry");
		tr.putForTest("가상양파", "Mock onion");
		tr.putForTest("통깨", "Whole sesame seeds");
		assertThat(tr.t("방울토마토 150g(5개)")).isEqualTo("Cherry tomato 150 g (5 pc)");
		assertThat(tr.t("물 2ml(1/3작은술)")).isEqualTo("Water 2 ml (1/3 tsp)");
		assertThat(tr.t("부추 10g(5줄기)")).isEqualTo("Chives 10 g (5 stalk)");
		assertThat(tr.t("다진 마늘 2.5g(1/2쪽)")).isEqualTo("Minced garlic 2.5 g (1/2 clove)");
		assertThat(tr.t("양파 10g(3×1cm)")).isEqualTo("Onion 10 g (3×1 cm)");
		assertThat(tr.t("통깨 약간")).isEqualTo("Whole sesame seeds a little");
		assertThat(tr.t("양파 1개(중간 크기)")).isEqualTo("Onion 1 pc"); // 옮길 수 없는 괄호 설명은 뺀다
		assertThat(tr.t("모르는재료 10g(1큰술)")).isEqualTo("모르는재료 10g(1큰술)"); // 이름 번역이 없으면 원문
		// 식품 이름: 첫 조각은 이름 번역, 뒤 조각은 상태 사전
		assertThat(tr.d("가상토마토, 가상방울, 생것")).isEqualTo("Mock tomato, Mock cherry, raw");
		assertThat(tr.d("가상양파, 말린것")).isEqualTo("Mock onion, dried");
		assertThat(tr.d("생것")).isEqualTo("생것"); // 조각 사전은 이름 전체로 쓰지 않는다
		assertThat(tr.d("가상양파, 이상한것")).isEqualTo("가상양파, 이상한것");
	}
}
