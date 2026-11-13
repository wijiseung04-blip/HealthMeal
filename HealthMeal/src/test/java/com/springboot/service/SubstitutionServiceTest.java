package com.springboot.service;

import com.springboot.nutrition.NutritionEngine.Comparison;
import com.springboot.nutrition.NutritionEngine.RateKind;
import com.springboot.service.SubstitutionService.Verdict;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}
