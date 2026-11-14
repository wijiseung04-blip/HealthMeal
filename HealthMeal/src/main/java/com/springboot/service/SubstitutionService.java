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
import java.util.regex.Pattern;

/**
 * 재료의 조리 역할과 영양성분을 고려해 감량·대체 후보를 만들고 계산 엔진으로 검증한다. AI 호출 없음.
 * 후보 종류:
 * - 감량: 양념(조미료류) 20·30·50%, 기름·설탕은 조리 순서로 역할을 판단해 볶음 기름·맛 내기일 때만 20·30%
 * - 같은 식품의 다른 부위·형태: 주 단백질·탄수화물 베이스·유제품을 식품 DB에서 같은 대표명·같은 조리 상태로 찾는다 (삼겹살→안심, 백미→현미)
 * - 대체 규칙: data/mapping/substitute-pool.csv 중 사람 또는 AI 교차검토로 확인된 것
 * - 묶음: 하나만으로 목표(10%)에 못 미치면 서로 다른 재료의 최선 후보를 2~3개 함께 적용
 * 실제로 목표 방향으로 영양소를 움직이는 것만 남긴다.
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
	/** 같은 식품의 다른 부위·형태 후보는 재료마다 이 개수까지 */
	static final int VARIANTS_PER_LINE = 2;
	/** 묶음은 최대 이 개수의 재료까지 */
	static final int MAX_BUNDLE = 3;

	/** 화면에 0.0으로 보일 만큼 작은 변화는 후보로 보이지 않는다 */
	static final double MIN_DELTA = 0.05;
	/** 감량 대상이 되려면 목표 영양소 합계의 1% 이상을 차지해야 한다 */
	static final double MIN_SHARE = 0.01;

	/** 식약처 1일 영양성분 기준치 (식품 등의 표시기준) */
	public static final Map<String, Double> DAILY_VALUES = Map.of(
			"에너지", 2000.0, "탄수화물", 324.0, "당류", 100.0, "단백질", 55.0, "지방", 54.0,
			"총 포화 지방산", 15.0, "나트륨", 2000.0, "총 식이섬유", 25.0, "콜레스테롤", 300.0);
	/** 늘면 나쁜 영양소 / 줄면 나쁜 영양소: 다른 영양소가 10% 넘게 나빠지면 경고 */
	static final Set<String> LIMIT_NUTRIENTS = Set.of("에너지", "당류", "지방", "총 포화 지방산", "나트륨", "콜레스테롤");
	static final Set<String> GOOD_NUTRIENTS = Set.of("단백질", "총 식이섬유");

	// 같은 식품의 다른 부위·형태로 쓰지 않는 것: 내장·뼈, 가공·조리 형태
	private static final Pattern VARIANT_EXCLUDED = Pattern.compile(
			"(^|, )(간|심장|허파|신장|내장|위|대장|소창|천엽|선지|꼬리|족|머리|뼈|비장|췌장|자궁|맹장|직장|껍질|골수|양\\(위\\))(\\(|,|$)"
					+ "|튀긴것|조미|통조림|훈제|젓갈|가루|양념|절임|레토르트|분말|농축|건조|말린것");
	private static final Set<String> STATE_WORDS = Set.of("생것", "삶은것", "데친것", "구운것", "찐것", "볶은것", "말린것", "냉동", "훈제", "밥");
	/** 곡류는 더 거친 곡물로, 유제품은 지방·당을 줄인 것으로만 바꾼다 */
	private static final Pattern BETTER_GRAIN = Pattern.compile("현미|분도미|발아|통밀|잡곡|흑미|보리");
	private static final Pattern LIGHTER_DAIRY = Pattern.compile("저지방|무지방|탈지|저당|무가당");

	private final RecipeNutritionService nutritionService;
	private final JdbcTemplate jdbc;

	public enum ChangeType { REDUCE, SUBSTITUTE, VARIANT, BUNDLE }

	public enum Verdict {
		ACHIEVED,     // 모든 재료 확인 + 목표 방향 10% 이상
		NOT_ENOUGH,   // 10% 미만 (상한값조차 10% 미만 포함)
		PENDING       // 미확인 재료가 있어 상한값만 알 수 있음
	}

	/** 맛·식감 영향 */
	public enum Impact {
		LOW("낮음"), MEDIUM("보통"), HIGH("큼");

		private final String label;

		Impact(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}
	}

	/**
	 * foodCode: 바꾼 뒤의 식품코드(감량이면 원래 식품). parts: 묶음이면 구성 후보 id.
	 * basis: 판단 근거 (기름·설탕 역할, 같은 식품의 다른 부위 등)
	 */
	public record Option(String id, int lineIndex, ChangeType type, String ingredient, String description, String candidateFoodName,
						 double ratio, Double gramsBefore, Double gramsAfter, Comparison goalComparison, Verdict verdict, String caution,
						 String basis, String foodCode, Impact impact, List<String> parts) {
	}

	/** 검수 대기 후보: 계산하지 않고 존재만 안내한다 */
	public record PendingOption(String ingredient, String description) {
	}

	/** roles: 재료별 조리 역할 (ingredients와 같은 순서) */
	public record Suggestions(RecipeNutrition base, NutritionGoal goal, List<Option> options, List<PendingOption> pending, List<String> roles) {

		/** 화면 상태: 대체 가능 / 검수 대기 / 대체 후보 없음 */
		public String state() {
			return !options.isEmpty() ? "AVAILABLE" : !pending.isEmpty() ? "REVIEW_PENDING" : "NONE";
		}
	}

	/** 다른 영양소가 나빠지는 경우 경고 */
	public record SideEffect(String nutrient, double rate) {
	}

	/** 선택한 후보의 전후 비교 (주요 영양소 전체, 1일 기준치 대비) */
	public record Applied(Option option, List<Comparison> comparisons, List<IngredientLine> afterLines, List<Integer> changedLines,
						  List<SideEffect> sideEffects) {

		public Double dailyPercent(String nutrient, Double value) {
			Double dv = DAILY_VALUES.get(nutrient);
			return dv == null || value == null ? null : value / dv * 100;
		}
	}

	private record PoolRule(String candidateCode, String candidateName, double minRatio, double maxRatio, String caution, boolean confirmed) {
	}

	/** 재료 하나를 바꾸는 내용 */
	private record Change(int index, String code, double grams) {
	}

	public Optional<Suggestions> suggest(Long recipeId, NutritionGoal goal) {
		Optional<RecipeNutrition> found = nutritionService.calculate(recipeId);
		if (found.isEmpty()) return Optional.empty();
		RecipeNutrition base = found.get();
		List<IngredientLine> lines = base.ingredients();
		Map<String, List<PoolRule>> pool = loadPool(lines);
		List<String> steps = steps(recipeId);
		List<String> roles = new ArrayList<>();
		for (IngredientLine l : lines) roles.add(role(l, steps));
		Map<Integer, List<String[]>> variants = loadVariants(lines, roles);

		Set<String> codes = new HashSet<>();
		lines.forEach(l -> {
			if (l.foodCode() != null) codes.add(l.foodCode());
		});
		pool.values().forEach(rules -> rules.forEach(r -> codes.add(r.candidateCode())));
		variants.values().forEach(v -> v.forEach(f -> codes.add(f[0])));
		NutritionEngine.NutrientSource source = nutritionService.per100gOf(codes);

		List<Option> options = new ArrayList<>();
		List<PendingOption> pending = new ArrayList<>();
		for (int i = 0; i < lines.size(); i++) {
			IngredientLine l = lines.get(i);
			// 사용량·식품을 모르는 재료는 바꿔도 변화량을 계산할 수 없다
			if (l.grams() == null || l.foodCode() == null) continue;
			// 목표 영양소에 거의 기여하지 않는 재료는 건너뛴다 (예: 저나트륨 목표에서 설탕)
			if (!goal.isIncrease() && !contributes(base.result(), i, goal.getNutrient())) continue;

			if (!goal.isIncrease() && l.foodGroup() != null) {
				if (AUTO_REDUCE_GROUPS.contains(l.foodGroup())) {
					for (double ratio : REDUCE_RATIOS) {
						evaluate(base, i, ChangeType.REDUCE, reduceText(ratio), l.foodCode(), null, ratio, null, null, reduceImpact(ratio), goal, source)
								.ifPresent(options::add);
					}
				} else if (IngredientRoleClassifier.applies(l.foodGroup())) {
					Classification c = IngredientRoleClassifier.classify(l.name(), l.foodGroup(), steps);
					if (c.role().reducible()) {
						String basis = c.role().label() + " — “" + c.evidence() + "”";
						for (double ratio : ROLE_REDUCE_RATIOS) {
							evaluate(base, i, ChangeType.REDUCE, reduceText(ratio), l.foodCode(), null, ratio, null, basis, reduceImpact(ratio), goal, source)
									.ifPresent(options::add);
						}
					} else {
						pending.add(new PendingOption(l.rawText(), "사용량 감량 — " + c.role().label() + (c.role() == IngredientRoleClassifier.Role.UNKNOWN ? ", 검수 필요" : ", 줄이면 조리가 달라져 감량하지 않음")));
					}
				}
			}
			// 같은 식품의 다른 부위·형태: 목표를 가장 많이 개선하는 것부터
			List<Option> byVariant = new ArrayList<>();
			for (String[] v : variants.getOrDefault(i, List.of())) {
				String basis = roles.get(i) + " — 같은 식품(" + baseName(l.foodName()) + ")의 다른 부위·형태, 같은 중량";
				evaluate(base, i, ChangeType.VARIANT, withRo(v[1]) + " 바꾸기", v[0], v[1], 1.0, variantCaution(roles.get(i)), basis,
						roles.get(i).equals(ROLE_DAIRY) ? Impact.LOW : Impact.MEDIUM, goal, source).ifPresent(byVariant::add);
			}
			byVariant.stream().sorted(improvement(goal)).limit(VARIANTS_PER_LINE).forEach(options::add);

			for (PoolRule rule : pool.getOrDefault(l.foodCode(), List.of())) {
				String desc = withRo(rule.candidateName()) + " 대체";
				if (!rule.confirmed()) {
					pending.add(new PendingOption(l.rawText(), desc));
					continue;
				}
				// 기본은 같은 중량(1:1). 허용 범위 밖이면 범위 안으로 맞춘다
				double ratio = Math.clamp(1.0, rule.minRatio(), rule.maxRatio());
				evaluate(base, i, ChangeType.SUBSTITUTE, desc, rule.candidateCode(), rule.candidateName(), ratio, rule.caution(), null,
						rule.caution() == null || rule.caution().isBlank() ? Impact.LOW : Impact.MEDIUM, goal, source).ifPresent(options::add);
			}
		}
		// 목표 영양소를 가장 많이 개선하는 순
		options.sort(improvement(goal));
		bundle(base, options, goal, source).ifPresent(b -> options.addFirst(b));
		return Optional.of(new Suggestions(base, goal, options, pending, roles));
	}

	public Optional<Applied> apply(Long recipeId, NutritionGoal goal, String optionId) {
		return suggest(recipeId, goal).flatMap(s -> s.options().stream().filter(o -> o.id().equals(optionId)).findFirst().map(o -> {
			List<IngredientLine> lines = s.base().ingredients();
			List<Option> parts = o.type() == ChangeType.BUNDLE
					? s.options().stream().filter(x -> o.parts().contains(x.id())).toList() : List.of(o);
			List<Change> changes = parts.stream().map(p -> new Change(p.lineIndex(), p.foodCode(), p.gramsAfter())).toList();
			List<Item> afterItems = replaced(s.base(), changes);
			Set<String> codes = new HashSet<>();
			afterItems.forEach(it -> {
				if (it.foodCode() != null) codes.add(it.foodCode());
			});
			NutritionEngine.NutrientSource source = nutritionService.per100gOf(codes);
			Result before = s.base().result();
			Result after = NutritionEngine.calculate(afterItems, NutritionEngine.KEY_NUTRIENTS, source);
			List<Integer> indexes = changes.stream().map(Change::index).toList();
			List<Comparison> comparisons = NutritionEngine.KEY_NUTRIENTS.stream()
					.map(n -> NutritionEngine.compare(n, before, after, changedDelta(before, after, indexes, n))).toList();

			List<IngredientLine> afterLines = new ArrayList<>(lines);
			for (Option p : parts) {
				IngredientLine old = lines.get(p.lineIndex());
				afterLines.set(p.lineIndex(), new IngredientLine(old.rawText() + " → " + p.description(), old.name(), p.gramsAfter(), old.gramsSource(),
						p.foodCode(), p.candidateFoodName() != null ? p.candidateFoodName() : old.foodName(), old.matchGrade(), old.foodGroup()));
			}
			return new Applied(o, comparisons, afterLines, indexes, sideEffects(comparisons, goal));
		}));
	}

	/** 목표 영양소가 아닌 것 중 10% 넘게 나빠지는 영양소 */
	static List<SideEffect> sideEffects(List<Comparison> comparisons, NutritionGoal goal) {
		List<SideEffect> out = new ArrayList<>();
		for (Comparison c : comparisons) {
			if (c.nutrient().equals(goal.getNutrient()) || c.rate() == null) continue;
			boolean worse = LIMIT_NUTRIENTS.contains(c.nutrient()) ? c.rate() >= NutritionGoal.THRESHOLD
					: GOOD_NUTRIENTS.contains(c.nutrient()) && c.rate() <= -NutritionGoal.THRESHOLD;
			if (worse) out.add(new SideEffect(c.nutrient(), c.rate()));
		}
		return out;
	}

	/**
	 * 하나만으로는 목표(10%)에 못 미칠 때: 재료마다 가장 좋은 후보를 골라 개선 폭 순으로 더해 가며 목표에 닿는 묶음을 만든다.
	 * 다른 영양소가 나빠지는 후보는 묶음에 넣지 않는다.
	 */
	private Optional<Option> bundle(RecipeNutrition base, List<Option> options, NutritionGoal goal, NutritionEngine.NutrientSource source) {
		if (options.stream().anyMatch(o -> o.verdict() == Verdict.ACHIEVED)) return Optional.empty();
		Map<Integer, Option> bestPerLine = new LinkedHashMap<>();
		for (Option o : options) bestPerLine.putIfAbsent(o.lineIndex(), o); // options는 개선 폭 순
		if (bestPerLine.size() < 2) return Optional.empty();

		List<Option> chosen = new ArrayList<>();
		Comparison last = null;
		for (Option o : bestPerLine.values()) {
			if (chosen.size() >= MAX_BUNDLE) break;
			List<Option> trial = new ArrayList<>(chosen);
			trial.add(o);
			List<Change> changes = trial.stream().map(p -> new Change(p.lineIndex(), p.foodCode(), p.gramsAfter())).toList();
			Result after = NutritionEngine.calculate(replaced(base, changes), NutritionEngine.KEY_NUTRIENTS, source);
			List<Integer> idx = changes.stream().map(Change::index).toList();
			List<Comparison> all = NutritionEngine.KEY_NUTRIENTS.stream()
					.map(n -> NutritionEngine.compare(n, base.result(), after, changedDelta(base.result(), after, idx, n))).toList();
			if (!sideEffects(all, goal).isEmpty()) continue;
			chosen = trial;
			last = all.stream().filter(c -> c.nutrient().equals(goal.getNutrient())).findFirst().orElseThrow();
			if (verdict(last) == Verdict.ACHIEVED) break;
		}
		if (chosen.size() < 2 || last == null || last.delta() == null) return Optional.empty();
		Impact impact = chosen.stream().map(Option::impact).max(Comparator.naturalOrder()).orElse(Impact.LOW);
		String id = "B-" + String.join("+", chosen.stream().map(Option::id).toList());
		return Optional.of(new Option(id, -1, ChangeType.BUNDLE,
				String.join(" + ", chosen.stream().map(Option::ingredient).toList()),
				String.join(", ", chosen.stream().map(o -> o.ingredient() + " " + o.description()).toList()),
				null, 1.0, null, null, last, verdict(last), null,
				"재료 하나로는 목표(10%)에 못 미쳐 " + chosen.size() + "가지를 함께 바꿈", null, impact, chosen.stream().map(Option::id).toList()));
	}

	private Optional<Option> evaluate(RecipeNutrition base, int index, ChangeType type, String desc, String newCode, String newName, double ratio,
									  String caution, String basis, Impact impact, NutritionGoal goal, NutritionEngine.NutrientSource source) {
		IngredientLine l = base.ingredients().get(index);
		double gramsAfter = l.grams() * ratio;
		Result before = base.result();
		Result after = NutritionEngine.calculate(replaced(base, List.of(new Change(index, newCode, gramsAfter))), NutritionEngine.KEY_NUTRIENTS, source);
		Double delta = changedDelta(before, after, List.of(index), goal.getNutrient());
		// 바뀐 재료의 목표 영양소를 알 수 없거나, 목표 방향이 아니거나, 변화가 무시할 만큼 작으면 뺀다
		if (delta == null || !goal.improves(delta) || Math.abs(delta) < MIN_DELTA) return Optional.empty();
		Comparison c = NutritionEngine.compare(goal.getNutrient(), before, after, delta);
		// 부위·형태 바꾸기는 목표 영양소 합계의 1% 이상 바뀔 때만 (나트륨 목표에서 고기 부위 차이 같은 잡음 제외)
		if (type == ChangeType.VARIANT && (c.rate() == null || Math.abs(c.rate()) < MIN_SHARE)) return Optional.empty();
		String id = index + "-" + type + "-" + (type == ChangeType.REDUCE ? String.valueOf(ratio) : newCode);
		return Optional.of(new Option(id, index, type, l.rawText(), desc, newName, ratio, l.grams(), gramsAfter, c, verdict(c), caution, basis,
				newCode, impact, List.of()));
	}

	// ---- 조리 역할 ----

	static final String ROLE_PROTEIN = "주 단백질";
	static final String ROLE_CARB = "탄수화물 베이스";
	static final String ROLE_DAIRY = "유제품";
	static final String ROLE_BINDER = "결착·농도";

	/** 재료의 조리 역할: 식품군과 조리 순서로 정한다 (기름·설탕은 조리 순서 규칙) */
	static String role(IngredientLine l, List<String> steps) {
		String g = l.foodGroup() == null ? "" : l.foodGroup();
		if ("WATER".equals(l.foodCode())) return "물·국물";
		if (IngredientRoleClassifier.applies(g)) return IngredientRoleClassifier.classify(l.name(), g, steps).role().label();
		if (g.contains("육류") || g.contains("어패류") || g.contains("난류") || g.contains("두류")) return ROLE_PROTEIN;
		if (g.contains("감자") || g.contains("전분")) return l.grams() != null && l.grams() < 15 ? ROLE_BINDER : ROLE_CARB;
		if (g.contains("곡류")) return ROLE_CARB;
		if (g.contains("우유") || g.contains("유제품")) return ROLE_DAIRY;
		if (g.contains("채소") || g.contains("버섯") || g.contains("해조") || g.contains("과일")) return "채소·과일";
		if (g.contains("조미료") || g.contains("장류")) return "양념(짠맛·향)";
		if (g.contains("견과") || g.contains("종실")) return "고소한 맛·토핑";
		return l.foodCode() == null ? "식품 미확인" : "기타";
	}

	private static String variantCaution(String role) {
		return switch (role) {
			case ROLE_PROTEIN -> "부위가 바뀌면 지방이 줄어 식감이 퍽퍽해질 수 있음, 익힘 시간 조절";
			case ROLE_CARB -> "식감이 거칠어질 수 있고 물·불리는 시간이 더 필요할 수 있음";
			default -> null;
		};
	}

	/**
	 * 같은 식품(대표명)의 다른 부위·형태 후보. 재료별 {코드, 식품명}.
	 * 식품명 구간 수가 같고 딱 한 구간만 다르며 조리 상태가 같은 것만 (돼지고기, 삼겹살, 생것 → 돼지고기, 안심, 생것).
	 * 고기는 부위·등급, 곡류는 현미·분도미·잡곡 등 더 거친 곡물, 유제품은 저지방·저당만. 내장·가공 형태 제외
	 */
	private Map<Integer, List<String[]>> loadVariants(List<IngredientLine> lines, List<String> roles) {
		Map<Integer, List<String[]>> out = new HashMap<>();
		for (int i = 0; i < lines.size(); i++) {
			IngredientLine l = lines.get(i);
			String r = roles.get(i);
			if (l.foodCode() == null || l.foodName() == null || l.grams() == null) continue;
			boolean meat = r.equals(ROLE_PROTEIN) && l.foodGroup() != null && l.foodGroup().contains("육류");
			if (!meat && !r.equals(ROLE_CARB) && !r.equals(ROLE_DAIRY)) continue;
			List<String[]> found = new ArrayList<>();
			jdbc.query("SELECT code, name FROM foods WHERE base_name = ? AND code <> ?", rs -> {
				String name = rs.getString(2);
				if (isVariant(l.foodName(), name, meat ? null : r.equals(ROLE_CARB) ? BETTER_GRAIN : LIGHTER_DAIRY)) {
					found.add(new String[]{rs.getString(1), name});
				}
			}, baseName(l.foodName()), l.foodCode());
			if (!found.isEmpty()) out.put(i, found);
		}
		return out;
	}

	static String baseName(String foodName) {
		return foodName == null ? "" : foodName.split(",")[0].trim();
	}

	/** 조리 상태 구간(생것·삶은것 등): 어느 구간에 있든 */
	static String state(String foodName) {
		for (String seg : foodName.split(",")) if (STATE_WORDS.contains(seg.trim())) return seg.trim();
		return null;
	}

	/** required: 바뀐 구간이 맞아야 하는 표현 (고기는 null = 부위·등급 무엇이든) */
	static boolean isVariant(String original, String candidate, Pattern required) {
		if (VARIANT_EXCLUDED.matcher(candidate).find() || !Objects.equals(state(original), state(candidate))) return false;
		String[] a = original.split(","), b = candidate.split(",");
		if (a.length != b.length) return false;
		int diff = -1;
		for (int i = 0; i < a.length; i++) {
			if (a[i].trim().equals(b[i].trim())) continue;
			if (diff >= 0) return false; // 두 구간 이상 다르면 다른 식품
			diff = i;
		}
		return diff > 0 && (required == null || required.matcher(b[diff]).find());
	}

	// ---- 공통 ----

	private List<String> steps(Long recipeId) {
		return jdbc.queryForList("SELECT description FROM recipe_steps WHERE recipe_id = ? ORDER BY step_no", String.class, recipeId);
	}

	private static String reduceText(double ratio) {
		return Math.round((1 - ratio) * 100) + "% 감량";
	}

	/** 감량 폭이 클수록 맛 영향이 크다 */
	static Impact reduceImpact(double ratio) {
		return ratio >= 0.8 ? Impact.LOW : ratio >= 0.7 ? Impact.MEDIUM : Impact.HIGH;
	}

	private static Comparator<Option> improvement(NutritionGoal goal) {
		return Comparator.comparingDouble(o -> goal.isIncrease() ? -o.goalComparison().delta() : o.goalComparison().delta());
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

	/** 바뀐 재료들의 영양소 변화량 합. 하나라도 전후 값이 미측정이면 null */
	private static Double changedDelta(Result before, Result after, List<Integer> indexes, String nutrient) {
		double sum = 0;
		for (int index : indexes) {
			Double b = before.items().get(index).nutrients().get(nutrient);
			Double a = after.items().get(index).nutrients().get(nutrient);
			if (a == null || b == null) return null;
			sum += a - b;
		}
		return sum;
	}

	private static List<Item> replaced(RecipeNutrition base, List<Change> changes) {
		Map<Integer, Change> byIndex = new HashMap<>();
		changes.forEach(c -> byIndex.put(c.index(), c));
		List<Item> items = new ArrayList<>();
		List<IngredientLine> lines = base.ingredients();
		for (int i = 0; i < lines.size(); i++) {
			IngredientLine l = lines.get(i);
			Change c = byIndex.get(i);
			items.add(c != null ? new Item(l.rawText(), c.code(), c.grams()) : new Item(l.rawText(), l.foodCode(), l.grams()));
		}
		return items;
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
