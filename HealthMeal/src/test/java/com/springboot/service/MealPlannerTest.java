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
	}

	@Test
	void 조건을_모두_지키는_조합만_목표_순으로_보여_준다() {
		var t = MealPlanner.target(NutritionGoal.LOW_SODIUM, "F");
		var combos = MealPlanner.plan(Situation.HOME, foods, t, NutritionGoal.LOW_SODIUM, 0, 10);

		assertThat(combos).isNotEmpty().allSatisfy(c -> {
			assertThat(c.totals().sodium()).isLessThanOrEqualTo(600);
			assertThat(c.totals().energy()).isBetween(t.energyMin(), t.energyMax());
			assertThat(c.totals().protein()).isGreaterThanOrEqualTo(15);
		});
		// 나트륨 낮은 순
		assertThat(combos).extracting(c -> c.totals().sodium()).isSorted();
		// 짠 비빔밥·된장국, 단백질 부족·당 과다인 과일컵은 어떤 조합에도 없다
		assertThat(combos).flatExtracting(c -> c.items().stream().map(Food::name).toList())
				.doesNotContain("비빔밥", "된장국", "과일컵 브런치");
		// 반찬 2개 구성에서 같은 반찬을 두 번 쓰지 않는다
		assertThat(combos).allSatisfy(c -> assertThat(c.items().stream().map(Food::id).distinct().count()).isEqualTo(c.items().size()));
	}

	@Test
	void 같은_주식은_한_번만_나오고_다음_쪽은_다른_조합이다() {
		var t = MealPlanner.target(null, null);
		var first = MealPlanner.plan(Situation.HOME, foods, t, null, 0, 2);
		var second = MealPlanner.plan(Situation.HOME, foods, t, null, 1, 2);
		assertThat(first).extracting(MealPlanner::key).doesNotHaveDuplicates();
		assertThat(second).extracting(MealPlanner::key).doesNotContainAnyElementsOf(first.stream().map(MealPlanner::key).toList());
	}
}
