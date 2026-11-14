package com.springboot.pipeline;

import com.springboot.nutrition.NutritionEngine;
import com.springboot.nutrition.NutritionEngine.CoverageLevel;
import com.springboot.nutrition.NutritionEngine.Item;
import com.springboot.nutrition.NutritionEngine.Result;
import com.springboot.pipeline.FoodMatcher.Grade;
import com.springboot.pipeline.IngredientResolver.RecipeData;
import com.springboot.pipeline.IngredientResolver.Resolved;
import com.springboot.pipeline.ParsedIngredient.Status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * DB 없이 원본 데이터만으로 전체 레시피에 계산 엔진을 돌려 커버리지를 확인한다.
 * 결과: data/parsed/recipe-nutrition.csv, data/report/nutrition-coverage.md
 */
final class NutritionCheck {

	static void run() throws IOException {
		List<FoodRecord> foods = FoodSources.loadAll();
		NutritionEngine.NutrientSource source = source(foods);
		List<String> keys = NutritionEngine.KEY_NUTRIENTS;

		List<List<Object>> rows = new ArrayList<>();
		Map<String, Map<CoverageLevel, Integer>> levelCount = new LinkedHashMap<>();
		keys.forEach(k -> levelCount.put(k, new EnumMap<>(CoverageLevel.class)));
		Map<String, Integer> unknownCauses = new HashMap<>();
		int fullyComputed = 0;
		List<RecipeData> recipes = IngredientResolver.resolveAll(foods);
		for (RecipeData d : recipes) {
			Result r = NutritionEngine.calculate(toItems(d.ingredients()), keys, source);
			if (r.coverage().computedItems() == r.coverage().totalItems()) fullyComputed++;
			for (String k : keys) levelCount.get(k).merge(r.coverage(k), 1, Integer::sum);
			// 원인 집계는 원문 대신 재료명 + 이유로 묶는다
			d.ingredients().stream().filter(x -> x.parsed().status() != Status.NON_FOOD)
					.filter(x -> x.grams() == null || x.match() == null || x.match().grade() == Grade.UNMATCHED)
					.map(x -> x.parsed().name() + (x.grams() == null ? " (사용량 미확인)" : " (식품 미매칭)"))
					.distinct().forEach(u -> unknownCauses.merge(u, 1, Integer::sum));

			List<Object> row = new ArrayList<>(List.of(d.raw().path("RCP_SEQ").asString(""), d.raw().path("RCP_NM").asString("")));
			for (String k : keys) row.add(r.totals().get(k));
			row.add(r.coverage("나트륨").name());
			row.add((double) r.coverage().computedItems());
			row.add((double) r.coverage().totalItems());
			row.add(String.join(", ", r.unknownItems()));
			row.add(d.raw().path("INFO_ENG").asString(""));
			row.add(d.raw().path("INFO_NA").asString(""));
			rows.add(row);
		}
		List<String> header = new ArrayList<>(List.of("recipe_seq", "recipe_name"));
		keys.forEach(header::add);
		header.addAll(List.of("sodium_coverage", "computed_items", "total_items", "unknown_items", "original_info_eng_per_serving", "original_info_na_per_serving"));
		Csv.write(Path.of("data", "parsed", "recipe-nutrition.csv"), header, rows);

		StringBuilder md = new StringBuilder("# 레시피 영양 계산 커버리지\n\n");
		md.append("- 레시피 ").append(recipes.size()).append("개 (레시피 전체 기준 합계, 1인분 정규화 안 함)\n");
		md.append("- 모든 재료가 계산된 레시피: ").append(fullyComputed).append("개 (").append(pct(fullyComputed, recipes.size())).append(")\n\n");
		md.append("COMPLETE = 모든 재료 계산, PARTIAL = 일부 재료 미확인으로 합계가 하한값, NONE = 계산 불가\n\n");
		md.append("| 영양소 | COMPLETE | PARTIAL | NONE |\n| --- | ---: | ---: | ---: |\n");
		levelCount.forEach((k, v) -> md.append("| ").append(k).append(" | ")
				.append(pct(v.getOrDefault(CoverageLevel.COMPLETE, 0), recipes.size())).append(" | ")
				.append(pct(v.getOrDefault(CoverageLevel.PARTIAL, 0), recipes.size())).append(" | ")
				.append(pct(v.getOrDefault(CoverageLevel.NONE, 0), recipes.size())).append(" |\n"));
		md.append("\n## 계산에서 빠지는 재료 상위 30\n\n| 재료 | 레시피 수 |\n| --- | ---: |\n");
		unknownCauses.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
				.limit(30).forEach(e -> md.append("| ").append(e.getKey().replace("|", "/")).append(" | ").append(e.getValue()).append(" |\n"));
		Path out = Files.createDirectories(Path.of("data", "report")).resolve("nutrition-coverage.md");
		Files.writeString(out, md);
		System.out.println("영양 계산 커버리지: 모든 재료 계산 " + pct(fullyComputed, recipes.size()) + " → " + out.toAbsolutePath());
	}

	/** 조리 도구는 빼고, 미매칭이면 식품 없음으로 넘긴다 */
	static List<Item> toItems(List<Resolved> ingredients) {
		return ingredients.stream()
				.filter(x -> x.parsed().status() != Status.NON_FOOD)
				.map(x -> new Item(x.parsed().raw(),
						x.match() == null || x.match().grade() == Grade.UNMATCHED ? null : x.match().foodCode(),
						x.grams()))
				.collect(Collectors.toList());
	}

	static NutritionEngine.NutrientSource source(List<FoodRecord> foods) {
		Map<String, Map<String, Double>> byCode = new HashMap<>();
		for (FoodRecord f : foods) {
			Map<String, Double> m = new HashMap<>();
			for (FoodRecord.Nutrient n : f.nutrients()) m.putIfAbsent(n.name(), n.value());
			byCode.put(f.code(), m);
		}
		Map<String, Double> water = new HashMap<>();
		NutritionEngine.KEY_NUTRIENTS.forEach(k -> water.put(k, 0.0));
		byCode.put(FoodMatcher.WATER_CODE, water);
		return byCode::get;
	}

	private static String pct(long n, long total) {
		return String.format("%.1f%%", total == 0 ? 0 : 100.0 * n / total);
	}
}
