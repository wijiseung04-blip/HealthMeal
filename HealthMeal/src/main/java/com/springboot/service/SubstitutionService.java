package com.springboot.service;

import com.springboot.domain.NutritionGoal;
import com.springboot.nutrition.IngredientRoleClassifier;
import com.springboot.nutrition.IngredientRoleClassifier.Classification;
import com.springboot.nutrition.NutritionEngine;
import com.springboot.nutrition.NutritionEngine.Comparison;
import com.springboot.nutrition.NutritionEngine.Item;
import com.springboot.nutrition.NutritionEngine.RateKind;
import com.springboot.nutrition.NutritionEngine.Result;
import com.springboot.service.RecipeNutritionService.IngredientLine;
import com.springboot.service.RecipeNutritionService.RecipeNutrition;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 영양 목표에 맞춰 재료 감량·대체 후보를 만들고 계산 엔진으로 검증한다.
 * 후보는 실제로 목표 방향으로 영양소를 움직이는 것만 남긴다. AI 호출 없음.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SubstitutionService {

	/** 감량 단계 (남기는 비율): 20%·30%·50% 감량 */
	static final List<Double> REDUCE_RATIOS = List.of(0.8, 0.7, 0.5);
	/** 감량이 자동 승인되는 식품군: 간장·소금·된장 등은 줄여도 조리가 실패하지 않는다 */
	static final Set<String> AUTO_REDUCE_GROUPS = Set.of("조미료류");
	/** 기름·설탕류: 조리 순서로 역할을 판단해 볶음 기름·양념처럼 줄여도 되는 경우만 감량 후보로 만든다 */
	static final List<Double> ROLE_REDUCE_RATIOS = List.of(0.8, 0.7);

	/** 화면에 0.0으로 보일 만큼 작은 변화는 후보로 보이지 않는다 */
	static final double MIN_DELTA = 0.05;
	/** 감량 대상이 되려면 목표 영양소 합계의 1% 이상을 차지해야 한다 */
	static final double MIN_SHARE = 0.01;

	private final RecipeNutritionService nutritionService;
	private final JdbcTemplate jdbc;

	public enum ChangeType { REDUCE, SUBSTITUTE }

	public enum Verdict {
		ACHIEVED,     // 모든 재료 확인 + 목표 방향 10% 이상
		NOT_ENOUGH,   // 10% 미만 (상한값조차 10% 미만 포함)
		PENDING       // 미확인 재료가 있어 상한값만 알 수 있음
	}

	public record Option(String id, int lineIndex, ChangeType type, String ingredient, String description, String candidateFoodName,
						 double ratio, Double gramsBefore, Double gramsAfter, Comparison goalComparison, Verdict verdict, String caution,
						 String basis) { // basis: 규칙 판단 근거 (기름·설탕류 감량)
	}

	/** 검수 대기 후보: 계산하지 않고 존재만 안내한다 */
	public record PendingOption(String ingredient, String description) {
	}

	public record Suggestions(RecipeNutrition base, NutritionGoal goal, List<Option> options, List<PendingOption> pending) {

		/** 화면 상태: 대체 가능 / 검수 대기 / 대체 후보 없음 */
		public String state() {
			return !options.isEmpty() ? "AVAILABLE" : !pending.isEmpty() ? "REVIEW_PENDING" : "NONE";
		}
	}

	/** 선택한 후보의 전후 비교 (주요 영양소 전체) */
	public record Applied(Option option, List<Comparison> comparisons, List<IngredientLine> afterLines) {
	}

	private record PoolRule(String candidateCode, String candidateName, double minRatio, double maxRatio, String caution, boolean confirmed) {
	}

	public Optional<Suggestions> suggest(Long recipeId, NutritionGoal goal) {
		Optional<RecipeNutrition> found = nutritionService.calculate(recipeId);
		if (found.isEmpty()) return Optional.empty();
		RecipeNutrition base = found.get();
		List<IngredientLine> lines = base.ingredients();
		Map<String, List<PoolRule>> pool = loadPool(lines);

		Set<String> codes = new HashSet<>();
		lines.forEach(l -> {
			if (l.foodCode() != null) codes.add(l.foodCode());
		});
		pool.values().forEach(rules -> rules.forEach(r -> codes.add(r.candidateCode())));
		NutritionEngine.NutrientSource source = nutritionService.per100gOf(codes);

		List<Option> options = new ArrayList<>();
		List<PendingOption> pending = new ArrayList<>();
		List<String> steps = null; // 기름·설탕류가 있을 때만 읽는다
		for (int i = 0; i < lines.size(); i++) {
			IngredientLine l = lines.get(i);
			// 사용량·식품을 모르는 재료는 바꿔도 변화량을 계산할 수 없다
			if (l.grams() == null || l.foodCode() == null) continue;
			// 목표 영양소에 거의 기여하지 않는 재료는 건너뛴다 (예: 저나트륨 목표에서 설탕)
			if (!goal.isIncrease() && !contributes(base.result(), i, goal.getNutrient())) continue;

			if (!goal.isIncrease() && l.foodGroup() != null) {
				if (AUTO_REDUCE_GROUPS.contains(l.foodGroup())) {
					for (double ratio : REDUCE_RATIOS) {
						String desc = Math.round((1 - ratio) * 100) + "% 감량";
						evaluate(base, i, ChangeType.REDUCE, desc, l.foodCode(), null, ratio, null, null, goal, source).ifPresent(options::add);
					}
				} else if (IngredientRoleClassifier.applies(l.foodGroup())) {
					if (steps == null) steps = steps(recipeId);
					Classification c = IngredientRoleClassifier.classify(l.name(), l.foodGroup(), steps);
					if (c.role().reducible()) {
						String basis = c.role().label() + " — “" + c.evidence() + "”";
						for (double ratio : ROLE_REDUCE_RATIOS) {
							String desc = Math.round((1 - ratio) * 100) + "% 감량";
							evaluate(base, i, ChangeType.REDUCE, desc, l.foodCode(), null, ratio, null, basis, goal, source).ifPresent(options::add);
						}
					} else {
						pending.add(new PendingOption(l.rawText(), "사용량 감량 — " + c.role().label() + (c.role() == IngredientRoleClassifier.Role.UNKNOWN ? ", 검수 필요" : ", 줄이면 조리가 달라져 감량하지 않음")));
					}
				}
			}
			for (PoolRule rule : pool.getOrDefault(l.foodCode(), List.of())) {
				String desc = withRo(rule.candidateName()) + " 대체";
				if (!rule.confirmed()) {
					pending.add(new PendingOption(l.rawText(), desc));
					continue;
				}
				// 기본은 같은 중량(1:1). 허용 범위 밖이면 범위 안으로 맞춘다
				double ratio = Math.clamp(1.0, rule.minRatio(), rule.maxRatio());
				evaluate(base, i, ChangeType.SUBSTITUTE, desc, rule.candidateCode(), rule.candidateName(), ratio, rule.caution(), null, goal, source)
						.ifPresent(options::add);
			}
		}
		// 목표 영양소를 가장 많이 개선하는 순
		options.sort(Comparator.comparingDouble(o -> goal.isIncrease() ? -o.goalComparison().delta() : o.goalComparison().delta()));
		return Optional.of(new Suggestions(base, goal, options, pending));
	}

	public Optional<Applied> apply(Long recipeId, NutritionGoal goal, String optionId) {
		return suggest(recipeId, goal).flatMap(s -> s.options().stream().filter(o -> o.id().equals(optionId)).findFirst().map(o -> {
			List<IngredientLine> lines = s.base().ingredients();
			List<Item> afterItems = replaced(s.base(), o.lineIndex(), codeOf(o, lines), o.gramsAfter());
			Set<String> codes = new HashSet<>();
			afterItems.forEach(it -> {
				if (it.foodCode() != null) codes.add(it.foodCode());
			});
			lines.forEach(l -> {
				if (l.foodCode() != null) codes.add(l.foodCode());
			});
			NutritionEngine.NutrientSource source = nutritionService.per100gOf(codes);
			Result before = s.base().result();
			Result after = NutritionEngine.calculate(afterItems, NutritionEngine.KEY_NUTRIENTS, source);
			List<Comparison> comparisons = NutritionEngine.KEY_NUTRIENTS.stream()
					.map(n -> NutritionEngine.compare(n, before, after, itemDelta(before, after, o.lineIndex(), n))).toList();

			List<IngredientLine> afterLines = new ArrayList<>(lines);
			IngredientLine old = lines.get(o.lineIndex());
			afterLines.set(o.lineIndex(), new IngredientLine(old.rawText() + " → " + o.description(), old.name(), o.gramsAfter(), old.gramsSource(),
					codeOf(o, lines), o.type() == ChangeType.SUBSTITUTE ? o.candidateFoodName() : old.foodName(), old.matchGrade(), old.foodGroup()));
			return new Applied(o, comparisons, afterLines);
		}));
	}

	private Optional<Option> evaluate(RecipeNutrition base, int index, ChangeType type, String desc, String newCode, String newName,
									  double ratio, String caution, String basis, NutritionGoal goal, NutritionEngine.NutrientSource source) {
		IngredientLine l = base.ingredients().get(index);
		double gramsAfter = l.grams() * ratio;
		Result before = base.result();
		Result after = NutritionEngine.calculate(replaced(base, index, newCode, gramsAfter), NutritionEngine.KEY_NUTRIENTS, source);
		Double delta = itemDelta(before, after, index, goal.getNutrient());
		// 바뀐 재료의 목표 영양소를 알 수 없거나, 목표 방향이 아니거나, 변화가 무시할 만큼 작으면 뺀다
		if (delta == null || !goal.improves(delta) || Math.abs(delta) < MIN_DELTA) return Optional.empty();
		Comparison c = NutritionEngine.compare(goal.getNutrient(), before, after, delta);
		String id = index + "-" + type + "-" + (type == ChangeType.REDUCE ? String.valueOf(ratio) : newCode);
		return Optional.of(new Option(id, index, type, l.rawText(), desc, newName, ratio, l.grams(), gramsAfter, c, verdict(c), caution, basis));
	}

	private List<String> steps(Long recipeId) {
		return jdbc.queryForList("SELECT description FROM recipe_steps WHERE recipe_id = ? ORDER BY step_no", String.class, recipeId);
	}

	private static boolean contributes(Result result, int index, String nutrient) {
		Double v = result.items().get(index).nutrients().get(nutrient);
		double total = result.totals().getOrDefault(nutrient, 0.0);
		return v != null && total > 0 && v / total >= MIN_SHARE;
	}

	/** 받침에 맞는 조사: 우유로, 생것으로, 올리고당으로 */
	static String withRo(String word) {
		char last = word.charAt(word.length() - 1);
		if (last < 0xAC00 || last > 0xD7A3) return word + "(으)로";
		int jong = (last - 0xAC00) % 28;
		return word + (jong == 0 || jong == 8 ? "로" : "으로"); // 받침 없음 또는 ㄹ 받침 → 로
	}

	static Verdict verdict(Comparison c) {
		if (c.rate() == null) return Verdict.PENDING;
		double rate = Math.abs(c.rate());
		if (rate < NutritionGoal.THRESHOLD) return Verdict.NOT_ENOUGH; // 상한값이 기준 미만이면 실제도 미만
		return c.rateKind() == RateKind.EXACT ? Verdict.ACHIEVED : Verdict.PENDING;
	}

	/** 바뀐 재료 하나의 영양소 변화량. 전후 값 중 하나라도 미측정이면 null */
	private static Double itemDelta(Result before, Result after, int index, String nutrient) {
		Double b = before.items().get(index).nutrients().get(nutrient);
		Double a = after.items().get(index).nutrients().get(nutrient);
		return a == null || b == null ? null : a - b;
	}

	private static List<Item> replaced(RecipeNutrition base, int index, String code, Double grams) {
		List<Item> items = new ArrayList<>();
		List<IngredientLine> lines = base.ingredients();
		for (int i = 0; i < lines.size(); i++) {
			IngredientLine l = lines.get(i);
			items.add(i == index ? new Item(l.rawText(), code, grams) : new Item(l.rawText(), l.foodCode(), l.grams()));
		}
		return items;
	}

	private static String codeOf(Option o, List<IngredientLine> lines) {
		return o.type() == ChangeType.REDUCE ? lines.get(o.lineIndex()).foodCode() : o.id().substring(o.id().lastIndexOf('-') + 1);
	}

	private Map<String, List<PoolRule>> loadPool(List<IngredientLine> lines) {
		Set<String> sources = new HashSet<>();
		lines.forEach(l -> {
			if (l.foodCode() != null) sources.add(l.foodCode());
		});
		Map<String, List<PoolRule>> pool = new HashMap<>();
		if (sources.isEmpty()) return pool;
		String in = String.join(",", Collections.nCopies(sources.size(), "?"));
		jdbc.query("SELECT p.source_food_code, p.candidate_food_code, f.name, p.min_ratio, p.max_ratio, p.caution, p.confirmed "
						+ "FROM substitute_pool p JOIN foods f ON f.code = p.candidate_food_code WHERE p.source_food_code IN (" + in + ") ORDER BY p.id",
				rs -> {
					pool.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(new PoolRule(rs.getString(2), rs.getString(3),
							rs.getDouble(4), rs.getDouble(5), rs.getString(6), rs.getBoolean(7)));
				}, sources.toArray());
		return pool;
	}
}
