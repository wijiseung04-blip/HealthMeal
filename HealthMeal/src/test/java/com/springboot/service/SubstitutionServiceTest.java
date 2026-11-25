package com.springboot.service;

import com.springboot.nutrition.NutritionEngine.Comparison;
import com.springboot.nutrition.NutritionEngine.RateKind;
import com.springboot.service.SubstitutionService.Verdict;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 감량·대체 서비스: 달성 판정(정확한 변화율 10% 이상), 조사 붙이기, 대체 규칙 파일 읽기 */
class SubstitutionServiceTest {

	private static Comparison c(Double rate, RateKind kind) {
		return new Comparison("나트륨", 100.0, 80.0, -20.0, rate, kind, true);
	}

	@Test
	void 판정은_정확한_변화율이_10퍼센트_이상일_때만_달성이다() {
		assertThat(SubstitutionService.verdict(c(-0.15, RateKind.EXACT))).isEqualTo(Verdict.ACHIEVED);
		assertThat(SubstitutionService.verdict(c(-0.05, RateKind.EXACT))).isEqualTo(Verdict.NOT_ENOUGH);
		// 상한값이 10% 이상이면 실제는 모르므로 보류, 상한값조차 10% 미만이면 미달 확정
		assertThat(SubstitutionService.verdict(c(-0.15, RateKind.UPPER_BOUND))).isEqualTo(Verdict.PENDING);
		assertThat(SubstitutionService.verdict(c(-0.05, RateKind.UPPER_BOUND))).isEqualTo(Verdict.NOT_ENOUGH);
		assertThat(SubstitutionService.verdict(c(null, RateKind.NOT_AVAILABLE))).isEqualTo(Verdict.PENDING);
	}

	@Test
	void 받침에_맞는_조사를_붙인다() {
		assertThat(SubstitutionService.withRo("우유")).isEqualTo("우유로");
		assertThat(SubstitutionService.withRo("달걀, 난백, 생것")).isEqualTo("달걀, 난백, 생것으로");
		assertThat(SubstitutionService.withRo("시럽, 올리고당")).isEqualTo("시럽, 올리고당으로");
		assertThat(SubstitutionService.withRo("현미, 밥")).isEqualTo("현미, 밥으로");
		assertThat(SubstitutionService.withRo("물")).isEqualTo("물로");
	}

	@Test
	void 대체_규칙의_조리_역할_칸을_읽는다() {
		assertThat(SubstitutionService.culinaryRoles("COOKING_MEDIUM|FLAVOR")).containsExactlyInAnyOrder(
				com.springboot.nutrition.IngredientAnalysis.CulinaryRole.COOKING_MEDIUM, com.springboot.nutrition.IngredientAnalysis.CulinaryRole.FLAVOR);
		// 예전 한글 설명이나 빈 칸은 제한 없음
		assertThat(SubstitutionService.culinaryRoles("유제품·크림")).isEmpty();
		assertThat(SubstitutionService.culinaryRoles(null)).isEmpty();
	}
}
