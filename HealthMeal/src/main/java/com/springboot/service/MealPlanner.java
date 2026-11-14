package com.springboot.service;

import com.springboot.domain.NutritionGoal;
import com.springboot.pipeline.MealItemLoader.Situation;
import com.springboot.pipeline.MealItemLoader.Slot;

import java.util.*;
import java.util.function.ToDoubleFunction;

/**
 * 한 끼 목표(열량 범위·나트륨 상한·단백질 하한) 안에 들어오는 식사 조합을 찾는다. DB·웹과 분리된 순수 계산.
 * 상황별 구성(틀)을 정해 두고, 각 칸 후보를 목표에 유리한 순으로 추린 뒤 모든 조합을 확인한다.
 */
public final class MealPlanner {

	/** 한 끼 기준: 하루 섭취기준의 1/3 */
	public static final double DAY_SODIUM_LIMIT = 2300; // 2020 한국인 영양소 섭취기준 나트륨 만성질환위험감소섭취량(mg/일)
	public static final double LOW_SODIUM_MEAL = 600;   // 저나트륨 목표: 하루 약 1,800mg 수준
	public static final double HIGH_PROTEIN_MEAL = 25;  // 고단백 목표: 한 끼 25g 이상
	public static final double BASE_PROTEIN_MEAL = 15;  // 모든 조합: 한 끼 단백질 15g 이상 (하루 약 50g의 1/3)
	public static final double SUGAR_MEAL = 25;         // 모든 조합: 당류 25g 이하 (당류 정보가 있는 조합만, 과일 자연당 포함)

	private static final int MAIN_POOL = 150;
	private static final int OTHER_POOL = 50;


	public record Food(long id, Slot slot, String name, String maker, Double grams, double energy, Double protein, Double fat,
					   Double carbohydrate, Double sugar, double sodium, String sourceCode) {
		double proteinOr0() {
			return protein == null ? 0 : protein;
		}
	}

	public record Target(double dayEnergy, double energyMin, double energyMax, double sodiumMax, double proteinMin, double sugarMax) {
		public double mealEnergy() {
			return dayEnergy / 3;
		}
	}

	public record Totals(double energy, double protein, double fat, double carbohydrate, Double sugar, double sodium, boolean partial) {
	}

	public record Combo(List<Food> items, Totals totals) {
	}

	/** 상황별 한 끼 구성 */
	static final Map<Situation, List<List<Slot>>> TEMPLATES = Map.of(
			Situation.CONVENIENCE, List.of(
					List.of(Slot.MAIN), List.of(Slot.MAIN, Slot.SIDE), List.of(Slot.MAIN, Slot.DRINK), List.of(Slot.MAIN, Slot.SIDE, Slot.DRINK)),
			// 외식은 메뉴 하나를 주문한다: 한 그릇 메뉴, 또는 국·찌개 + 공기밥(백반). 기본 반찬은 따로 고르지 않는다
			Situation.EATING_OUT, List.of(List.of(Slot.MAIN), List.of(Slot.STAPLE, Slot.SOUP)),
			Situation.HOME, List.of(
					List.of(Slot.MAIN), List.of(Slot.MAIN, Slot.SIDE), List.of(Slot.STAPLE, Slot.SOUP, Slot.SIDE), List.of(Slot.STAPLE, Slot.SIDE, Slot.SIDE)));

	private MealPlanner() {
	}

	/** sex: "M" / "F" / null(모름). 하루 열량은 2020 섭취기준 성인 에너지필요추정량을 어림한 값 */
	public static Target target(NutritionGoal goal, String sex) {
		double day = "M".equals(sex) ? 2500 : "F".equals(sex) ? 2000 : 2200;
		double meal = day / 3;
		double min = meal * 0.8, max = meal * 1.2;
		double sodium = DAY_SODIUM_LIMIT / 3;
		double protein = BASE_PROTEIN_MEAL;
		if (goal == NutritionGoal.LOW_SODIUM) sodium = LOW_SODIUM_MEAL;
		if (goal == NutritionGoal.LOW_CALORIE) {
			min = meal * 0.6;
			max = meal * 0.9;
		}
		if (goal == NutritionGoal.HIGH_PROTEIN) protein = HIGH_PROTEIN_MEAL;
		return new Target(day, round(min), round(max), round(sodium), protein, SUGAR_MEAL);
	}

	public static List<Combo> plan(Situation situation, List<Food> foods, Target t, NutritionGoal goal, int page, int size) {
		Map<Slot, List<Food>> pools = new EnumMap<>(Slot.class);
		for (Slot slot : Slot.values()) {
			pools.put(slot, foods.stream()
					.filter(f -> f.slot() == slot && f.energy() <= t.energyMax() && f.sodium() <= t.sodiumMax())
					.sorted(density(goal))
					.limit(slot == Slot.MAIN ? MAIN_POOL : OTHER_POOL).toList());
		}
		// 조합 수가 수십만이 될 수 있으므로 주식(없으면 국, 그것도 없으면 첫 반찬)마다 가장 좋은 조합 하나만 남긴다
		Comparator<Combo> ranking = ranking(goal, t);
		Map<String, Combo> bestByKey = new HashMap<>();
		for (List<Slot> template : TEMPLATES.get(situation)) collect(template, pools, t, new ArrayList<>(), 0, bestByKey, ranking);
		List<Combo> found = new ArrayList<>(bestByKey.values());
		found.sort(ranking);

		// 한 구성(틀)만 몰리지 않게
		List<Combo> diverse = new ArrayList<>();
		Map<List<Slot>, Integer> perShape = new HashMap<>();
		int perShapeLimit = Math.max(2, (page + 1) * size / 2);
		for (Combo c : found) {
			List<Slot> shape = c.items().stream().map(Food::slot).toList();
			if (perShape.getOrDefault(shape, 0) >= perShapeLimit) continue;
			perShape.merge(shape, 1, Integer::sum);
			diverse.add(c);
			if (diverse.size() >= (page + 1) * size) break;
		}
		// 구성 제한 때문에 자리가 남으면 나머지 조합으로 채운다
		for (Combo c : found) {
			if (diverse.size() >= (page + 1) * size) break;
			if (!diverse.contains(c)) diverse.add(c);
		}
		return diverse.subList(Math.min(diverse.size(), page * size), diverse.size());
	}

	/** 칸을 하나씩 채우며 조건을 넘으면 더 내려가지 않는다. 같은 칸이 두 번이면(반찬 2개) 순서만 다른 조합은 건너뛴다 */
	private static void collect(List<Slot> template, Map<Slot, List<Food>> pools, Target t, List<Food> chosen, int from,
								Map<String, Combo> out, Comparator<Combo> ranking) {
		if (chosen.size() == template.size()) {
			Totals s = totals(chosen);
			boolean ok = s.energy() >= t.energyMin() && s.energy() <= t.energyMax() && s.sodium() <= t.sodiumMax()
					&& s.protein() >= t.proteinMin() && (s.sugar() == null || s.sugar() <= t.sugarMax());
			if (!ok) return;
			Combo c = new Combo(List.copyOf(chosen), s);
			out.merge(key(c), c, (a, b) -> ranking.compare(a, b) <= 0 ? a : b);
			return;
		}
		Slot slot = template.get(chosen.size());
		boolean repeat = chosen.size() > 0 && template.get(chosen.size() - 1) == slot;
		List<Food> pool = pools.get(slot);
		double energy = chosen.stream().mapToDouble(Food::energy).sum();
		double sodium = chosen.stream().mapToDouble(Food::sodium).sum();
		for (int i = repeat ? from + 1 : 0; i < pool.size(); i++) {
			Food f = pool.get(i);
			if (energy + f.energy() > t.energyMax() || sodium + f.sodium() > t.sodiumMax()) continue;
			chosen.add(f);
			collect(template, pools, t, chosen, i, out, ranking);
			chosen.removeLast();
		}
	}

	static String key(Combo c) {
		return c.items().stream().filter(f -> f.slot() == Slot.MAIN).findFirst()
				.or(() -> c.items().stream().filter(f -> f.slot() == Slot.SOUP).findFirst())
				.orElse(c.items().stream().filter(f -> f.slot() != Slot.STAPLE).findFirst().orElse(c.items().getFirst()))
				.name();
	}

	static Totals totals(List<Food> items) {
		double e = 0, p = 0, fat = 0, c = 0, sugar = 0, na = 0;
		boolean partial = false, sugarKnown = true;
		for (Food f : items) {
			e += f.energy();
			na += f.sodium();
			if (f.protein() == null || f.fat() == null || f.carbohydrate() == null) partial = true;
			p += f.protein() == null ? 0 : f.protein();
			fat += f.fat() == null ? 0 : f.fat();
			c += f.carbohydrate() == null ? 0 : f.carbohydrate();
			if (f.sugar() == null) sugarKnown = false;
			else sugar += f.sugar();
		}
		return new Totals(round(e), round(p), round(fat), round(c), sugarKnown ? round(sugar) : null, round(na), partial);
	}

	/** 칸별 후보를 추리는 기준: 열량당 나트륨이 적거나 단백질이 많은 것 */
	private static Comparator<Food> density(NutritionGoal goal) {
		ToDoubleFunction<Food> naPerKcal = f -> f.sodium() / f.energy();
		ToDoubleFunction<Food> proteinPerKcal = f -> -f.proteinOr0() / f.energy();
		Comparator<Food> c = goal == NutritionGoal.HIGH_PROTEIN || goal == NutritionGoal.LOW_CALORIE
				? Comparator.comparingDouble(proteinPerKcal).thenComparingDouble(naPerKcal)
				: Comparator.comparingDouble(naPerKcal).thenComparingDouble(proteinPerKcal);
		return c.thenComparing(Food::name);
	}

	/** 최종 순위: 목표 영양소 우선, 목표가 없으면 한 끼 열량에 가깝고 나트륨이 적은 것 */
	private static Comparator<Combo> ranking(NutritionGoal goal, Target t) {
		Comparator<Combo> c;
		if (goal == NutritionGoal.LOW_SODIUM) c = Comparator.comparingDouble(x -> x.totals().sodium());
		else if (goal == NutritionGoal.LOW_CALORIE) c = Comparator.comparingDouble(x -> x.totals().energy());
		else if (goal == NutritionGoal.HIGH_PROTEIN) c = Comparator.comparingDouble(x -> -x.totals().protein());
		else c = Comparator.comparingDouble(x -> Math.abs(x.totals().energy() - t.mealEnergy()) / t.mealEnergy() + x.totals().sodium() / t.sodiumMax());
		return c.thenComparingDouble(x -> -x.totals().protein()).thenComparing(x -> x.items().getFirst().name());
	}

	private static double round(double v) {
		return Math.round(v * 10) / 10.0;
	}
}
