package com.springboot.service;

import com.springboot.domain.NutritionGoal;
import com.springboot.pipeline.MealItemLoader.Situation;
import com.springboot.pipeline.MealItemLoader.Slot;
import com.springboot.service.MealPlanner.Food;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MealPlannerTest {

	private static long id = 0;

	private static Food food(Slot slot, String name, double kcal, double sodium, double protein, Double sugar) {
		return new Food(++id, slot, name, null, 100.0, kcal, protein, 5.0, 50.0, sugar, sodium, "c" + id);
	}

	private final List<Food> foods = List.of(
			food(Slot.STAPLE, "쌀밥 1공기", 340, 0, 6, 0.0),
			food(Slot.SOUP, "된장국", 80, 700, 6, 2.0),       // 짜다
			food(Slot.SOUP, "미역국", 90, 300, 8, 1.0),
			food(Slot.SIDE, "두부조림", 150, 250, 12, 3.0),
			food(Slot.SIDE, "시금치나물", 60, 150, 3, 1.0),
			food(Slot.SIDE, "닭가슴살구이", 180, 200, 30, 0.0),
			food(Slot.MAIN, "비빔밥", 600, 900, 18, 8.0),      // 짜다
			food(Slot.MAIN, "닭가슴살샐러드밥", 520, 450, 35, 6.0),
			food(Slot.MAIN, "과일컵 브런치", 600, 50, 5, 60.0)); // 단백질 부족·당 과다

	@Test
	void 한_끼_목표는_하루_기준의_3분의_1이고_목표마다_조건이_다르다() {
		var base = MealPlanner.target(null, "F");
		assertThat(base.mealEnergy()).isEqualTo(2000.0 / 3);
		assertThat(base.sodiumMax()).isEqualTo(766.7);
		assertThat(MealPlanner.target(NutritionGoal.LOW_SODIUM, "F").sodiumMax()).isEqualTo(600);
		assertThat(MealPlanner.target(NutritionGoal.LOW_CALORIE, "F").energyMax()).isLessThan(base.energyMax());
		assertThat(MealPlanner.target(NutritionGoal.HIGH_PROTEIN, "M").proteinMin()).isEqualTo(25);
		assertThat(MealPlanner.target(NutritionGoal.LOW_SUGAR, "F").sugarMax()).isEqualTo(15);
		assertThat(base.sugarMax()).isEqualTo(25);
	}

	@Test
	void 적합도_점수순으로_목표를_지킨_조합이_먼저이고_이유와_상태를_단다() {
		var t = MealPlanner.target(NutritionGoal.LOW_SODIUM, "F");
		var result = MealPlanner.recommend(Situation.HOME, foods, t, NutritionGoal.LOW_SODIUM, java.util.Set.of(), 0, 10);
		var combos = result.combos();

		assertThat(result.status()).isEqualTo(MealPlanner.Status.TARGET_SATISFIED);
		assertThat(combos).extracting(MealPlanner.Combo::score).isSorted();
		// 목표를 모두 지킨 조합(점수 ≈ 0)이 앞에, 일부 미달은 뒤에
		int firstPartial = combos.stream().map(MealPlanner.Combo::status).toList().indexOf(MealPlanner.Status.PARTIALLY_SATISFIED);
		if (firstPartial >= 0) assertThat(combos.subList(0, firstPartial)).allMatch(c -> c.status() == MealPlanner.Status.TARGET_SATISFIED);
		assertThat(combos.getFirst()).satisfies(c -> {
			assertThat(c.totals().sodium()).isLessThanOrEqualTo(600);
			assertThat(c.totals().energy()).isBetween(t.energyMin(), t.energyMax());
			assertThat(c.checks()).anySatisfy(k -> {
				assertThat(k.primary()).isTrue();
				assertThat(k.text()).startsWith("나트륨").contains("600 mg 이하");
			});
		});
		// 짠 비빔밥(나트륨 안전 상한 900mg 초과)·단백질 부족·당 과다인 과일컵은 목표를 지킨 조합에 없다
		assertThat(combos.stream().filter(c -> c.status() == MealPlanner.Status.TARGET_SATISFIED))
				.flatExtracting(c -> c.items().stream().map(Food::name).toList())
				.doesNotContain("비빔밥", "된장국", "과일컵 브런치");
		// 반찬 2개 구성에서 같은 반찬을 두 번 쓰지 않는다
		assertThat(combos).allSatisfy(c -> assertThat(c.items().stream().map(Food::id).distinct().count()).isEqualTo(c.items().size()));
	}

	@Test
	void 제외_식품은_빼고_조건에_맞는_조합이_없으면_추천하지_않는다() {
		var t = MealPlanner.target(null, "F");
		var noChicken = MealPlanner.recommend(Situation.HOME, foods, t, null, java.util.Set.of("닭 가슴살"), 0, 10);
		assertThat(noChicken.excludedFoods()).isEqualTo(2); // 닭가슴살구이·닭가슴살샐러드밥 (공백 무시)
		assertThat(noChicken.combos()).flatExtracting(c -> c.items().stream().map(Food::name).toList()).noneMatch(n -> n.contains("닭가슴살"));

		var nothing = MealPlanner.recommend(Situation.HOME, List.of(food(Slot.MAIN, "아주 짠 국밥", 600, 3000, 20, 1.0)), t, null, java.util.Set.of(), 0, 10);
		assertThat(nothing.status()).isEqualTo(MealPlanner.Status.NO_VALID_COMBINATION);
		assertThat(nothing.combos()).isEmpty();
	}

	@Test
	void 주목표_정보가_없으면_정보_부족이다() {
		var t = MealPlanner.target(NutritionGoal.HIGH_PROTEIN, "M");
		Food noProtein = new Food(99, Slot.MAIN, "단백질 미표기 도시락", null, 300.0, 750, null, 20.0, 100.0, 5.0, 500, "x");
		var r = MealPlanner.recommend(Situation.CONVENIENCE, List.of(noProtein), t, NutritionGoal.HIGH_PROTEIN, java.util.Set.of(), 0, 10);
		assertThat(r.status()).isEqualTo(MealPlanner.Status.INSUFFICIENT_DATA);
	}

	@Test
	void 같은_주식은_한_번만_나오고_다음_쪽은_다른_조합이다() {
		var t = MealPlanner.target(null, null);
		var first = MealPlanner.plan(Situation.HOME, foods, t, null, 0, 2);
		var second = MealPlanner.plan(Situation.HOME, foods, t, null, 1, 2);
		assertThat(first).extracting(MealPlanner::key).doesNotHaveDuplicates();
		assertThat(second).extracting(MealPlanner::key).doesNotContainAnyElementsOf(first.stream().map(MealPlanner::key).toList());
	}

	@Test
	void 저당_목표는_당류를_주목표로_보고_당이_적은_조합을_먼저_낸다() {
		var t = MealPlanner.target(NutritionGoal.LOW_SUGAR, "F");
		var result = MealPlanner.recommend(Situation.HOME, foods, t, NutritionGoal.LOW_SUGAR, java.util.Set.of(), 0, 10);
		assertThat(result.combos()).isNotEmpty();
		var first = result.combos().getFirst();
		assertThat(first.totals().sugar()).isLessThanOrEqualTo(15.0);
		assertThat(first.checks()).anySatisfy(c -> {
			assertThat(c.nutrient()).isEqualTo("당류");
			assertThat(c.primary()).isTrue();
		});
		assertThat(result.combos()).noneMatch(c -> c.items().stream().anyMatch(f -> f.name().equals("과일컵 브런치")) && c.status() == MealPlanner.Status.TARGET_SATISFIED);
	}
}
