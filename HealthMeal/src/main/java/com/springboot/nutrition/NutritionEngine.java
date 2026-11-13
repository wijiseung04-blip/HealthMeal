package com.springboot.nutrition;

import java.util.*;

/**
 * 공통 영양 계산 엔진. 레시피·대체·상품 조합의 모든 영양 수치가 여기서 나온다.
 * DB에 의존하지 않는 순수 계산이며, 값이 없으면 0으로 메우지 않고 커버리지로 드러낸다.
 *
 * 재료 영양소량 = 100g당 영양소량 × 실제 사용량(g) / 100
 */
public final class NutritionEngine {

	/** 화면·비교에 쓰는 주요 영양소 (국가표준식품성분 영양소명) */
	public static final List<String> KEY_NUTRIENTS = List.of(
			"에너지", "탄수화물", "당류", "단백질", "지방", "총 포화 지방산", "나트륨", "총 식이섬유", "콜레스테롤");

	/** 계산할 재료 한 줄. foodCode가 null이면 식품 미매칭, grams가 null이면 사용량 미확인 */
	public record Item(String label, String foodCode, Double grams) {
	}

	/** 식품 100g당 영양소. 값이 null이거나 키가 없으면 미측정 */
	public interface NutrientSource {
		Map<String, Double> per100g(String foodCode);
	}

	public enum CoverageLevel {
		COMPLETE, // 모든 재료의 값이 있음
		PARTIAL,  // 일부 재료만 계산됨 → 합계는 하한값
		NONE      // 계산된 재료 없음
	}

	public record Coverage(int totalItems, int matchedItems, int knownAmountItems, int computedItems,
						   Double weightCoverage, Map<String, CoverageLevel> nutrientCoverage) {
	}

	/** 재료별 결과. nutrients는 값을 계산한 영양소만 담는다 */
	public record ItemResult(Item item, Map<String, Double> nutrients, List<String> missingNutrients) {
	}

	public record Result(Map<String, Double> totals, List<ItemResult> items, Coverage coverage, List<String> unknownItems) {

		public CoverageLevel coverage(String nutrient) {
			return coverage.nutrientCoverage().getOrDefault(nutrient, CoverageLevel.NONE);
		}
	}

	private NutritionEngine() {
	}

	public static Result calculate(List<Item> items, List<String> nutrients, NutrientSource source) {
		Map<String, Double> totals = new LinkedHashMap<>();
		Map<String, Integer> computedPerNutrient = new HashMap<>();
		nutrients.forEach(n -> {
			totals.put(n, 0.0);
			computedPerNutrient.put(n, 0);
		});
		List<ItemResult> results = new ArrayList<>();
		List<String> unknown = new ArrayList<>();
		int matched = 0, knownAmount = 0, computed = 0;
		double knownGrams = 0, matchedGrams = 0;

		for (Item item : items) {
			if (item.foodCode() != null) matched++;
			if (item.grams() != null) {
				knownAmount++;
				knownGrams += item.grams();
				if (item.foodCode() != null) matchedGrams += item.grams();
			}
			if (item.foodCode() == null || item.grams() == null) {
				unknown.add(item.label());
				results.add(new ItemResult(item, Map.of(), nutrients));
				continue;
			}
			computed++;
			Map<String, Double> per100 = source.per100g(item.foodCode());
			Map<String, Double> values = new LinkedHashMap<>();
			List<String> missing = new ArrayList<>();
			for (String n : nutrients) {
				Double v = per100 == null ? null : per100.get(n);
				if (v == null) {
					missing.add(n); // 미측정: 0으로 메우지 않는다
					continue;
				}
				double amount = v * item.grams() / 100;
				values.put(n, amount);
				totals.merge(n, amount, Double::sum);
				computedPerNutrient.merge(n, 1, Integer::sum);
			}
			results.add(new ItemResult(item, values, missing));
		}

		Map<String, CoverageLevel> level = new LinkedHashMap<>();
		for (String n : nutrients) {
			int c = computedPerNutrient.get(n);
			level.put(n, c == 0 ? CoverageLevel.NONE : c == items.size() ? CoverageLevel.COMPLETE : CoverageLevel.PARTIAL);
		}
		// 사용량을 모르는 재료가 하나라도 있으면 전체 중량을 알 수 없으므로 중량 커버리지는 산출하지 않는다
		Double weightCoverage = knownAmount == items.size() && knownGrams > 0 ? matchedGrams / knownGrams : null;
		Coverage coverage = new Coverage(items.size(), matched, knownAmount, computed, weightCoverage, level);
		return new Result(totals, results, coverage, unknown);
	}

	// ---------- 대체 전후 비교 ----------

	public enum RateKind {
		EXACT,        // 모든 재료 확인: 정확한 변화율
		UPPER_BOUND,  // 미확인 재료가 있음: 실제 변화율은 이보다 작거나 같다
		NOT_AVAILABLE // 대체 전 합계가 0: 변화량만 표시
	}

	/**
	 * @param delta 바뀐 재료들로 계산한 정확한 변화량. 바뀐 재료 중 미확인이 있으면 null(계산 불가)
	 */
	public record Comparison(String nutrient, Double before, Double after, Double delta, Double rate, RateKind rateKind, boolean computable) {
	}

	/**
	 * 대체 전후 비교. 미확인 재료 합계 U ≥ 0 이고 대체 전후 동일하므로
	 * 실제 변화율 |Δ|/(B+U) ≤ |Δ|/B 이다 (B: 확인된 재료의 대체 전 합계).
	 */
	public static Comparison compare(String nutrient, Result before, Result after, Double changedItemsDelta) {
		double b = before.totals().getOrDefault(nutrient, 0.0);
		double a = after.totals().getOrDefault(nutrient, 0.0);
		if (changedItemsDelta == null) {
			return new Comparison(nutrient, b, a, null, null, RateKind.NOT_AVAILABLE, false);
		}
		if (b <= 0) {
			return new Comparison(nutrient, b, a, changedItemsDelta, null, RateKind.NOT_AVAILABLE, true);
		}
		RateKind kind = before.coverage(nutrient) == CoverageLevel.COMPLETE ? RateKind.EXACT : RateKind.UPPER_BOUND;
		return new Comparison(nutrient, b, a, changedItemsDelta, changedItemsDelta / b, kind, true);
	}
}
