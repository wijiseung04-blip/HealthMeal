package com.springboot.checkup;

import com.springboot.checkup.CheckupRules.Judgment;
import com.springboot.checkup.CheckupRules.ServiceStatus;
import com.springboot.checkup.CheckupRules.Sex;
import com.springboot.domain.NutritionGoal;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CheckupRulesTest {

	private final CheckupRules rules = CheckupRules.load();

	private Judgment judgment(CheckupRules.Evaluation e, TestItem item) {
		return e.items().stream().filter(i -> i.item() == item).findFirst().orElseThrow().judgment();
	}

	@Test
	void 경계_수치는_관리_필요와_목표를_우선순위대로_추천한다() {
		var e = rules.evaluate(Map.of(TestItem.SYSTOLIC_BP, 138.0, TestItem.TRIGLYCERIDE, 178.0, TestItem.HDL, 45.0), Sex.M);
		assertThat(e.status()).isEqualTo(ServiceStatus.MANAGEMENT_REQUIRED);
		assertThat(e.recommendedGoals()).containsExactly(NutritionGoal.LOW_SODIUM, NutritionGoal.LOW_CALORIE);
		assertThat(judgment(e, TestItem.HDL)).isEqualTo(Judgment.NORMAL);
	}

	@Test
	void 질환_의심_수치는_의료기관_상담을_먼저_권한다() {
		var e = rules.evaluate(Map.of(TestItem.FASTING_GLUCOSE, 130.0), Sex.F);
		assertThat(e.status()).isEqualTo(ServiceStatus.MEDICAL_CONSULTATION_RECOMMENDED);
		assertThat(judgment(e, TestItem.FASTING_GLUCOSE)).isEqualTo(Judgment.HIGH);
	}

	@Test
	void 경계값은_기준에_포함된다() {
		assertThat(judgment(rules.evaluate(Map.of(TestItem.SYSTOLIC_BP, 120.0), Sex.M), TestItem.SYSTOLIC_BP)).isEqualTo(Judgment.BORDERLINE);
		assertThat(judgment(rules.evaluate(Map.of(TestItem.SYSTOLIC_BP, 119.0), Sex.M), TestItem.SYSTOLIC_BP)).isEqualTo(Judgment.NORMAL);
	}

	@Test
	void 허리둘레는_성별_기준을_쓴다() {
		assertThat(judgment(rules.evaluate(Map.of(TestItem.WAIST, 87.0), Sex.F), TestItem.WAIST)).isEqualTo(Judgment.HIGH);
		assertThat(judgment(rules.evaluate(Map.of(TestItem.WAIST, 87.0), Sex.M), TestItem.WAIST)).isEqualTo(Judgment.NORMAL);
	}

	@Test
	void 신장_기능_이상이면_주의_표시를_한다() {
		var e = rules.evaluate(Map.of(TestItem.EGFR, 52.0), Sex.M);
		assertThat(e.kidneyCaution()).isTrue();
		assertThat(e.status()).isEqualTo(ServiceStatus.MEDICAL_CONSULTATION_RECOMMENDED);
		assertThat(e.recommendedGoals()).doesNotContain(NutritionGoal.HIGH_PROTEIN);
	}

	@Test
	void 입력한_수치가_없으면_판정하지_않는다() {
		var e = rules.evaluate(Map.of(), Sex.M);
		assertThat(e.status()).isEqualTo(ServiceStatus.INSUFFICIENT_DATA);
		assertThat(e.items()).isEmpty();
	}
}
