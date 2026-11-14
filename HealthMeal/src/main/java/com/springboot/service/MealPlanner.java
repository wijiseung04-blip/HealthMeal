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

	/** 한 끼 목표 묶음. carbMin·carbMax는 개인 목표에 탄수화물 범위가 있을 때만 */
	public record Target(double dayEnergy, double energyMin, double energyMax, double sodiumMax, double proteinMin, double sugarMax,
						 Double carbMin, Double carbMax) {
		public Target(double dayEnergy, double energyMin, double energyMax, double sodiumMax, double proteinMin, double sugarMax) {
			this(dayEnergy, energyMin, energyMax, sodiumMax, proteinMin, sugarMax, null, null);
		}

		public double mealEnergy() {
			return dayEnergy / 3;
		}

		/** 반드시 지킬 조건: 한 끼로 볼 수 있는 열량, 나트륨 안전 상한 (목표보다 넓다) */
		double hardEnergyMin() {
			return energyMin * 0.75;
		}

		double hardEnergyMax() {
			return energyMax * 1.25;
		}

		double hardSodiumMax() {
			return sodiumMax * 1.5;
		}
	}

	public record Totals(double energy, double protein, double fat, double carbohydrate, Double sugar, double sodium, boolean partial) {
	}

	/** 결과 상태 (기획서 6-5) */
	public enum Status { TARGET_SATISFIED, PARTIALLY_SATISFIED, NO_VALID_COMBINATION, INSUFFICIENT_DATA }

	/** 목표 하나에 대한 확인 결과와 추천 이유 문장 */
	public record Check(String nutrient, boolean primary, boolean known, boolean met, String text) {
	}

	/** score: 적합도 점수 (낮을수록 좋음, 0이면 모든 목표 충족) */
	public record Combo(List<Food> items, Totals totals, double score, Status status, List<Check> checks) {
		public Combo(List<Food> items, Totals totals) {
			this(items, totals, 0, Status.TARGET_SATISFIED, List.of());
		}
	}

	/** 추천 결과: status는 가장 좋은 조합의 상태, 없으면 NO_VALID_COMBINATION */
	public record Result(List<Combo> combos, Status status, int excludedFoods) {
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

	/**
	 * 개인 기준으로 한 끼 목표를 만든다 (하루 값 ÷ 3). null인 값은 기본값을 쓴다.
	 * dayEnergy: 에너지필요추정량, energyDayMax: 열량 목표(상한), 나머지는 회원 목표 또는 하루 기준
	 */
	public static Target personalized(NutritionGoal goal, String sex, Double dayEnergy, Double energyDayMax, Double sodiumDayMax,
									  Double proteinDayMin, Double sugarDayMax) {
		return personalized(goal, sex, dayEnergy, energyDayMax, sodiumDayMax, proteinDayMin, sugarDayMax, null, null);
	}

	public static Target personalized(NutritionGoal goal, String sex, Double dayEnergy, Double energyDayMax, Double sodiumDayMax,
									  Double proteinDayMin, Double sugarDayMax, Double carbDayMin, Double carbDayMax) {
		Target base = target(goal, sex);
		double day = dayEnergy != null ? dayEnergy : base.dayEnergy();
		double meal = day / 3;
		double min = meal * (goal == NutritionGoal.LOW_CALORIE ? 0.6 : 0.8);
		double max = meal * (goal == NutritionGoal.LOW_CALORIE ? 0.9 : 1.2);
		if (energyDayMax != null) {
			max = Math.min(max, energyDayMax / 3);
			min = Math.min(min, max * 0.75);
		}
		double sodium = sodiumDayMax == null ? base.sodiumMax() : Math.min(base.sodiumMax(), sodiumDayMax / 3);
		double protein = proteinDayMin == null ? base.proteinMin() : Math.max(base.proteinMin(), proteinDayMin / 3);
		double sugar = sugarDayMax == null ? base.sugarMax() : Math.min(base.sugarMax(), sugarDayMax / 3);
		return new Target(day, round(min), round(max), round(sodium), round(protein), round(sugar),
				carbDayMin == null ? null : round(carbDayMin / 3), carbDayMax == null ? null : round(carbDayMax / 3));
	}

	public static List<Combo> plan(Situation situation, List<Food> foods, Target t, NutritionGoal goal, int page, int size) {
		return recommend(situation, foods, t, goal, Set.of(), page, size).combos();
	}

	/**
	 * 기획서 6-3: 제약(제외 식품·한 끼 열량·나트륨 안전 상한)으로 거른 뒤 칸별 상위 후보를 조합하고 적합도 점수순으로 정렬한다.
	 * excluded: 이름에 이 낱말이 들어간 품목은 쓰지 않는다 (공백 무시)
	 */
	public static Result recommend(Situation situation, List<Food> foods, Target t, NutritionGoal goal, Set<String> excluded, int page, int size) {
		List<String> words = excluded.stream().map(w -> w.replaceAll("\\s+", "")).filter(w -> !w.isEmpty()).toList();
		int excludedCount = 0;
		Map<Slot, List<Food>> pools = new EnumMap<>(Slot.class);
		for (Slot slot : Slot.values()) pools.put(slot, new ArrayList<>());
		for (Food f : foods) {
			String name = f.name().replaceAll("\\s+", "");
			if (words.stream().anyMatch(name::contains)) {
				excludedCount++;
				continue;
			}
			if (f.energy() <= t.hardEnergyMax() && f.sodium() <= t.hardSodiumMax()) pools.get(f.slot()).add(f);
		}
		for (Slot slot : Slot.values()) {
			pools.put(slot, pools.get(slot).stream().sorted(density(goal)).limit(slot == Slot.MAIN ? MAIN_POOL : OTHER_POOL).toList());
		}
		// 조합 수가 수십만이 될 수 있으므로 주식(없으면 국, 그것도 없으면 첫 반찬)마다 가장 좋은 조합 하나만 남긴다
		Comparator<Combo> ranking = Comparator.comparingDouble(Combo::score).thenComparing(c -> c.items().stream().map(Food::id).toList(),
				(a, b) -> Arrays.compare(a.stream().mapToLong(Long::longValue).toArray(), b.stream().mapToLong(Long::longValue).toArray()));
		Map<String, Combo> bestByKey = new HashMap<>();
		for (List<Slot> template : TEMPLATES.get(situation)) collect(template, pools, t, goal, new ArrayList<>(), 0, bestByKey, ranking);
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
		diverse.sort(ranking);
		List<Combo> pageItems = diverse.subList(Math.min(diverse.size(), page * size), diverse.size());
		Status status = found.isEmpty() ? Status.NO_VALID_COMBINATION : found.getFirst().status();
		return new Result(pageItems, status, excludedCount);
	}

	/** 칸을 하나씩 채우며 제약을 넘으면 더 내려가지 않는다. 같은 칸이 두 번이면(반찬 2개) 순서만 다른 조합은 건너뛴다 */
	private static void collect(List<Slot> template, Map<Slot, List<Food>> pools, Target t, NutritionGoal goal, List<Food> chosen, int from,
								Map<String, Combo> out, Comparator<Combo> ranking) {
		if (chosen.size() == template.size()) {
			Totals s = totals(chosen);
			if (s.energy() < t.hardEnergyMin() || s.energy() > t.hardEnergyMax() || s.sodium() > t.hardSodiumMax()) return;
			Combo c = evaluate(List.copyOf(chosen), s, t, goal);
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
			if (energy + f.energy() > t.hardEnergyMax() || sodium + f.sodium() > t.hardSodiumMax()) continue;
			chosen.add(f);
			collect(template, pools, t, goal, chosen, i, out, ranking);
			chosen.removeLast();
		}
	}

	/**
	 * 기획서 6-4 적합도 점수 = Σ 가중치 × 이탈량 / 기준값 (주목표 3, 나머지 1). 상한은 넘친 양, 하한은 모자란 양, 범위는 벗어난 양.
	 * 모든 목표를 지킨 조합끼리는 주목표 방향(나트륨·열량은 낮게, 단백질은 높게)으로 아주 작게 차이를 둔다.
	 */
	static Combo evaluate(List<Food> items, Totals s, Target t, NutritionGoal goal) {
		String primary = goal == null ? null : goal.getNutrient();
		List<Check> checks = new ArrayList<>();
		double score = 0;
		// 열량 (범위)
		double eDev = Math.max(0, t.energyMin() - s.energy()) + Math.max(0, s.energy() - t.energyMax());
		score += weight("에너지", primary) * eDev / Math.max(1, t.energyMax() - t.energyMin());
		checks.add(new Check("에너지", "에너지".equals(primary), true, eDev == 0, eDev == 0
				? "열량 %s kcal — 한 끼 기준 %s~%s kcal 안".formatted(n(s.energy()), n(t.energyMin()), n(t.energyMax()))
				: s.energy() < t.energyMin() ? "열량 %s kcal — 기준(%s kcal)보다 %s kcal 적음".formatted(n(s.energy()), n(t.energyMin()), n(eDev))
				: "열량 %s kcal — 기준(%s kcal)보다 %s kcal 많음".formatted(n(s.energy()), n(t.energyMax()), n(eDev))));
		// 나트륨 (상한)
		double naDev = Math.max(0, s.sodium() - t.sodiumMax());
		score += weight("나트륨", primary) * naDev / t.sodiumMax();
		checks.add(new Check("나트륨", "나트륨".equals(primary), true, naDev == 0, naDev == 0
				? "나트륨 %s mg — 한 끼 기준 %s mg 이하 (%s%%)".formatted(n(s.sodium()), n(t.sodiumMax()), n(s.sodium() / t.sodiumMax() * 100))
				: "나트륨 %s mg — 기준 %s mg보다 %s mg 많음".formatted(n(s.sodium()), n(t.sodiumMax()), n(naDev))));
		// 단백질 (하한): 단백질 정보가 없는 품목이 있으면 합계는 하한값
		boolean proteinKnown = items.stream().allMatch(f -> f.protein() != null);
		double pDev = Math.max(0, t.proteinMin() - s.protein());
		score += weight("단백질", primary) * pDev / t.proteinMin();
		checks.add(new Check("단백질", "단백질".equals(primary), proteinKnown || pDev == 0, pDev == 0, pDev == 0
				? "단백질 %s g — %s g 이상 충족".formatted(n1(s.protein()), n(t.proteinMin()))
				: "단백질 %s g — 하한 %s g보다 %s g 부족%s".formatted(n1(s.protein()), n(t.proteinMin()), n1(pDev), proteinKnown ? "" : " (정보 없는 품목 있음)")));
		// 당류 (상한, 정보가 있을 때만)
		if (s.sugar() == null) {
			checks.add(new Check("당류", false, false, false, "당류 — 정보가 없는 품목이 있어 확인하지 못함"));
		} else {
			double sDev = Math.max(0, s.sugar() - t.sugarMax());
			score += weight("당류", primary) * sDev / t.sugarMax();
			checks.add(new Check("당류", false, true, sDev == 0, sDev == 0 ? "당류 %s g — %s g 이하".formatted(n1(s.sugar()), n(t.sugarMax()))
					: "당류 %s g — 기준 %s g보다 %s g 많음".formatted(n1(s.sugar()), n(t.sugarMax()), n1(sDev))));
		}
		// 탄수화물 (개인 목표에 범위가 있을 때)
		if (t.carbMin() != null && t.carbMax() != null) {
			double cDev = Math.max(0, t.carbMin() - s.carbohydrate()) + Math.max(0, s.carbohydrate() - t.carbMax());
			score += cDev / Math.max(1, t.carbMax() - t.carbMin());
			checks.add(new Check("탄수화물", false, true, cDev == 0, cDev == 0
					? "탄수화물 %s g — 기준 %s~%s g 안".formatted(n(s.carbohydrate()), n(t.carbMin()), n(t.carbMax()))
					: "탄수화물 %s g — 기준 %s~%s g 밖".formatted(n(s.carbohydrate()), n(t.carbMin()), n(t.carbMax()))));
		}
		score += 0.01 * tieBreak(s, t, goal);

		boolean primaryUnknown = checks.stream().anyMatch(c -> c.primary() && !c.known());
		boolean allMet = checks.stream().filter(Check::known).allMatch(Check::met);
		Status status = primaryUnknown ? Status.INSUFFICIENT_DATA : allMet ? Status.TARGET_SATISFIED : Status.PARTIALLY_SATISFIED;
		return new Combo(items, s, Math.round(score * 10000) / 10000.0, status, checks);
	}

	private static double weight(String nutrient, String primary) {
		return nutrient.equals(primary) ? 3 : 1;
	}

	private static double tieBreak(Totals s, Target t, NutritionGoal goal) {
		if (goal == NutritionGoal.LOW_SODIUM) return s.sodium() / t.sodiumMax();
		if (goal == NutritionGoal.LOW_CALORIE) return s.energy() / t.energyMax();
		if (goal == NutritionGoal.HIGH_PROTEIN) return -s.protein() / t.proteinMin();
		return Math.abs(s.energy() - t.mealEnergy()) / t.mealEnergy() + s.sodium() / t.sodiumMax();
	}

	private static String n(double v) {
		return String.format("%,.0f", v);
	}

	private static String n1(double v) {
		return String.format("%,.1f", v);
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

	private static double round(double v) {
		return Math.round(v * 10) / 10.0;
	}
}
