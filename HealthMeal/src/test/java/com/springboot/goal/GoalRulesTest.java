package com.springboot.goal;

import com.springboot.checkup.CheckupRules;
import com.springboot.checkup.TestItem;
import com.springboot.domain.NutritionGoal;
import com.springboot.goal.GoalRules.Activity;
import com.springboot.goal.GoalRules.GoalType;
import com.springboot.goal.GoalRules.Profile;
import com.springboot.goal.GoalRules.Sex;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** 개인 영양 목표 규칙: 에너지필요추정량 계산, 검진 판정 → 주·보조 목표, 프로필이 없을 때 */
class GoalRulesTest {

	private static final CheckupRules RULES = CheckupRules.load();
	private final Profile man = new Profile(30, Sex.M, 175.0, 80.0, Activity.LOW_ACTIVE);

	@Test
	void 에너지필요추정량은_나이_성별_키_몸무게_활동계수로_계산한다() {
		// 662 − 9.53×30 + 1.11 × (15.91×80 + 539.6×1.75) = 2,837.1 → 10kcal 단위
		assertThat(GoalRules.energy(man)).isEqualTo(2840.0);
		// 354 − 6.91×40 + 1.0 × (9.36×55 + 726×1.60) = 1,754.0
		assertThat(GoalRules.energy(new Profile(40, Sex.F, 160.0, 55.0, Activity.SEDENTARY))).isEqualTo(1750.0);
		assertThat(GoalRules.energy(new Profile(30, Sex.M, null, 80.0, Activity.ACTIVE))).isNull(); // 하나라도 없으면 계산하지 않는다

		var ref = GoalRules.reference(man);
		assertThat(ref.proteinMin()).isEqualTo(73.0);       // 80kg × 0.91 = 72.8 → 1g 단위
		assertThat(ref.sodiumMax()).isEqualTo(2300);
		assertThat(ref.saturatedFatMax()).isEqualTo(22.0);  // 2840 × 7% ÷ 9 = 22.1 → 1g 단위
	}

	@Test
	void 검진_판정으로_주목표와_보조_목표를_만든다() {
		var e = RULES.evaluate(Map.of(TestItem.SYSTOLIC_BP, 138.0, TestItem.BMI, 26.7, TestItem.FASTING_GLUCOSE, 104.0), CheckupRules.Sex.M);

		assertThat(GoalRules.fromCheckup(e, man))
				.extracting(GoalRules.Item::priority, GoalRules.Item::nutrient, GoalRules.Item::type, GoalRules.Item::min, GoalRules.Item::max)
				.containsExactly(
						tuple(1, "나트륨", GoalType.MAX, null, 2000.0),           // 혈압 → 주목표
						tuple(2, "에너지", GoalType.MAX, null, 2340.0),           // 비만: 필요량 − 500
						tuple(3, "당류", GoalType.MAX, null, 71.0),               // 혈당: 당류 열량의 10%
						tuple(4, "탄수화물", GoalType.RANGE, 355.0, 426.0),       // 탄수화물 열량의 50~60%
						tuple(5, "총 식이섬유", GoalType.MIN, 30.0, null),        // 혈당: 남 30g
						tuple(6, "단백질", GoalType.MIN, 80.0, null));            // 체중 1kg당 1g
	}

	@Test
	void 프로필이_없으면_열량에_기대는_목표는_만들지_않고_신장_이상이면_단백질을_늘리지_않는다() {
		var e = RULES.evaluate(Map.of(TestItem.SYSTOLIC_BP, 145.0, TestItem.BMI, 27.0, TestItem.EGFR, 50.0), CheckupRules.Sex.F);
		Profile none = new Profile(null, Sex.F, null, 70.0, null);
		assertThat(GoalRules.fromCheckup(e, none)).extracting(GoalRules.Item::nutrient).containsExactly("나트륨");
		assertThat(GoalRules.fromCheckup(e, new Profile(50, Sex.F, 160.0, 70.0, Activity.SEDENTARY)))
				.extracting(GoalRules.Item::nutrient).containsExactly("나트륨", "에너지"); // 단백질 하한 없음
	}

	@Test
	void 주목표를_감량_대체의_목표_프리셋으로_바꾼다() {
		assertThat(GoalRules.preset("나트륨", GoalType.MAX)).contains(NutritionGoal.LOW_SODIUM);
		assertThat(GoalRules.preset("탄수화물", GoalType.RANGE)).contains(NutritionGoal.LOW_SUGAR);
		assertThat(GoalRules.preset("당류", GoalType.MAX)).contains(NutritionGoal.LOW_SUGAR);
		assertThat(GoalRules.preset("에너지", GoalType.MAX)).contains(NutritionGoal.LOW_CALORIE);
		assertThat(GoalRules.preset("단백질", GoalType.MIN)).contains(NutritionGoal.HIGH_PROTEIN);
	}

	@Test
	void 프로필도_검진도_없는_회원은_기준치가_비어_있다() {
		// 성별·체중이 모두 없을 때 NPE가 나던 문제 (식사 조합 화면 500)
		var ref = GoalRules.reference(new Profile(null, null, null, null, null));
		org.assertj.core.api.Assertions.assertThat(ref.proteinMin()).isNull();
		org.assertj.core.api.Assertions.assertThat(ref.personalized()).isFalse();
	}

	@Test
	void 혈당이_높으면_프로필이_없어도_2000kcal_기준으로_당류와_탄수화물_목표를_만든다() {
		var e = RULES.evaluate(Map.of(TestItem.FASTING_GLUCOSE, 112.0), CheckupRules.Sex.F);
		var items = GoalRules.fromCheckup(e, new Profile(null, Sex.F, null, null, null));
		assertThat(items).extracting(GoalRules.Item::priority, GoalRules.Item::nutrient, GoalRules.Item::type, GoalRules.Item::min, GoalRules.Item::max)
				.containsExactly(tuple(1, "당류", GoalType.MAX, null, 50.0), tuple(2, "탄수화물", GoalType.RANGE, 250.0, 300.0),
						tuple(3, "총 식이섬유", GoalType.MIN, 20.0, null));
		assertThat(items.getFirst().reason()).contains("공복혈당").contains("2,000kcal 기준");
		assertThat(GoalRules.preset(items.getFirst().nutrient(), items.getFirst().type())).contains(NutritionGoal.LOW_SUGAR);
	}

	@Test
	void 당화혈색소만_높아도_혈당_목표를_만든다() {
		var e = RULES.evaluate(Map.of(TestItem.HBA1C, 6.0, TestItem.FASTING_GLUCOSE, 95.0), CheckupRules.Sex.M);
		assertThat(GoalRules.fromCheckup(e, man)).extracting(GoalRules.Item::nutrient).containsExactly("당류", "탄수화물", "총 식이섬유");
		assertThat(GoalRules.fromCheckup(e, man).getFirst().reason()).startsWith("당화혈색소");
		// 정상이면 만들지 않는다
		assertThat(GoalRules.fromCheckup(RULES.evaluate(Map.of(TestItem.HBA1C, 5.4), CheckupRules.Sex.M), man)).isEmpty();
	}

	@Test
	void 콜레스테롤이_높으면_포화지방과_식이섬유_목표를_만든다() {
		var e = RULES.evaluate(Map.of(TestItem.LDL, 145.0), CheckupRules.Sex.M);
		assertThat(e.recommendedGoals()).containsExactly(NutritionGoal.LOW_SAT_FAT);
		// 프로필 있음: 2,840kcal × 7% ÷ 9 = 22.1g
		assertThat(GoalRules.fromCheckup(e, man))
				.extracting(GoalRules.Item::priority, GoalRules.Item::nutrient, GoalRules.Item::type, GoalRules.Item::min, GoalRules.Item::max)
				.containsExactly(tuple(1, "총 포화 지방산", GoalType.MAX, null, 22.0), tuple(2, "총 식이섬유", GoalType.MIN, 30.0, null));
		// 프로필 없음: 2,000kcal 기준 15.6g, 성별 모르면 식이섬유 25g
		var none = GoalRules.fromCheckup(e, new Profile(null, null, null, null, null));
		assertThat(none).extracting(GoalRules.Item::max).containsExactly(16.0, null);
		assertThat(none).extracting(GoalRules.Item::min).containsExactly(null, 25.0);
		assertThat(none.getFirst().reason()).contains("2,000kcal 기준");
		assertThat(GoalRules.preset("총 포화 지방산", GoalType.MAX)).contains(NutritionGoal.LOW_SAT_FAT);
		assertThat(GoalRules.preset("총 식이섬유", GoalType.MIN)).contains(NutritionGoal.HIGH_FIBER);
	}
}
