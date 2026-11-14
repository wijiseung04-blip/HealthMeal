package com.springboot.pipeline;

import com.springboot.pipeline.FoodMatcher.Grade;
import com.springboot.pipeline.FoodMatcher.Match;
import com.springboot.pipeline.FoodRecord.Nutrient;
import com.springboot.pipeline.ParsedIngredient.Status;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 재료명 → 식품 매칭, 단위 환산표 도출, 커버리지 리포트.
 * 사람이 확인한 결과는 data/mapping/ingredient-mappings-manual.csv 에 적으면 다음 실행부터 반영된다.
 */
final class IngredientMatching {

	static final Path MAPPING = Path.of("data", "mapping");
	static final Path MANUAL = MAPPING.resolve("ingredient-mappings-manual.csv");
	static final Path MANUAL_UNITS = MAPPING.resolve("unit-conversions-manual.csv");

	static void run() throws IOException {
		List<ParsedIngredient> items = new ArrayList<>();
		for (JsonNode r : RecipeRaw.load(Collector.RAW.resolve("cookrcp01"))) {
			items.addAll(IngredientParser.parse(r.path("RCP_PARTS_DTLS").asString("")));
		}
		List<FoodRecord> foods = FoodDetailParser.loadAll(Collector.RAW.resolve("food-detail"));
		FoodMatcher matcher = new FoodMatcher(foods, readManual());
		UnitConversions conversions = UnitConversions.derive(items);

		// 재료명별 사용 빈도 → 매칭
		Map<String, Long> uses = items.stream().filter(i -> !i.name().isBlank() && i.status() != Status.NON_FOOD)
				.collect(Collectors.groupingBy(ParsedIngredient::name, Collectors.counting()));
		Map<String, Match> matches = new HashMap<>();
		uses.keySet().forEach(n -> matches.put(n, matcher.match(n)));
		writeConversions(conversions); // 원문 도출값만 파일로
		configure(conversions, matches);

		writeFoods(foods);
		writeMappings(uses, matches);
		if (!Files.exists(MANUAL)) {
			Csv.write(MANUAL, List.of("ingredient", "food_code", "confirmed", "comment"), List.of());
		}
		writeReport(items, uses, matches, conversions, foods.size());
	}

	/** 원문 도출 환산표에 확인된 개당 중량과 같은 식품 밀도 공유를 더한다 */
	static void configure(UnitConversions conversions, Map<String, Match> matches) throws IOException {
		for (List<String> r : Csv.read(MANUAL_UNITS)) {
			if (r.size() >= 4 && "O".equalsIgnoreCase(r.get(3).trim()) && !r.get(2).isBlank()) {
				conversions.addManual(r.get(0), r.get(1), Double.parseDouble(r.get(2)));
			}
		}
		Map<String, String> codes = new HashMap<>();
		matches.forEach((n, m) -> {
			if (m != null && m.grade() != Grade.UNMATCHED) codes.put(n, m.foodCode());
		});
		conversions.linkSameFood(codes);
	}

	static Map<String, String[]> readManual() throws IOException {
		Map<String, String[]> m = new HashMap<>();
		for (List<String> r : Csv.read(MANUAL)) {
			if (r.size() >= 2 && !r.get(0).isBlank() && !r.get(1).isBlank()) {
				m.put(FoodMatcher.compact(r.get(0)), new String[]{r.get(1), r.size() > 2 ? r.get(2) : ""});
			}
		}
		return m;
	}

	private static void writeFoods(List<FoodRecord> foods) throws IOException {
		List<String> keys = List.of("에너지", "단백질", "지방", "탄수화물", "당류", "나트륨");
		List<List<Object>> rows = new ArrayList<>();
		for (FoodRecord f : foods) {
			List<Object> row = new ArrayList<>(List.of(f.code(), f.name(), nz(f.group()), nz(f.origin()), nz(f.examYear()), f.nutrients().size()));
			Map<String, Double> v = new HashMap<>();
			for (Nutrient n : f.nutrients()) v.putIfAbsent(n.name(), n.value());
			for (String k : keys) row.add(v.get(k));
			rows.add(row);
		}
		Csv.write(Path.of("data", "parsed", "foods.csv"),
				List.of("food_code", "food_name", "group", "origin", "exam_year", "nutrient_count", "energy_kcal", "protein_g", "fat_g", "carb_g", "sugar_g", "sodium_mg"), rows);
	}

	private static void writeConversions(UnitConversions c) throws IOException {
		List<List<Object>> rows = new ArrayList<>();
		for (var e : c.entries()) rows.add(List.of(e.name(), e.unit(), e.gramsPerUnit(), e.samples(), e.min(), e.max(), "COOKRCP01 병기 중앙값"));
		Csv.write(MAPPING.resolve("unit-conversions.csv"),
				List.of("ingredient", "unit", "grams_per_unit", "samples", "min", "max", "source"), rows);
	}

	private static void writeMappings(Map<String, Long> uses, Map<String, Match> matches) throws IOException {
		List<List<Object>> rows = new ArrayList<>();
		uses.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
				.forEach(e -> {
					Match m = matches.get(e.getKey());
					rows.add(Arrays.asList(e.getKey(), e.getValue().doubleValue(), m.grade().name(), m.rule(), m.foodCode(), m.foodName(), String.join(" | ", m.candidates())));
				});
		Csv.write(MAPPING.resolve("ingredient-mappings.csv"),
				List.of("ingredient", "uses", "grade", "rule", "food_code", "food_name", "candidates"), rows);
	}

	private static void writeReport(List<ParsedIngredient> items, Map<String, Long> uses, Map<String, Match> matches,
									UnitConversions conv, int foodCount) throws IOException {
		StringBuilder md = new StringBuilder("# 재료 매칭·환산 리포트\n\n");
		md.append("- 국가표준식품 ").append(foodCount).append("개, 레시피 재료명 ").append(uses.size()).append("종, 재료 항목 ").append(items.size()).append("개\n");
		md.append("- 단위 환산표: 원문 병기에서 도출한 재료×단위 ").append(conv.entries().size()).append("개\n\n");

		md.append("## 매칭 등급\n\n| 등급 | 재료명 종류 | 재료 항목(빈도) |\n| --- | ---: | ---: |\n");
		for (Grade g : Grade.values()) {
			long kinds = matches.values().stream().filter(m -> m.grade() == g).count();
			long occ = uses.entrySet().stream().filter(e -> matches.get(e.getKey()).grade() == g).mapToLong(Map.Entry::getValue).sum();
			md.append("| ").append(g).append(" | ").append(kinds).append(" (").append(pct(kinds, uses.size())).append(") | ")
					.append(occ).append(" (").append(pct(occ, items.size())).append(") |\n");
		}

		// 계산 가능: g을 알고(환산 포함) 식품이 매칭된 항목
		long withGrams = 0, converted = 0, computable = 0;
		for (ParsedIngredient i : items) {
			Double g = conv.toGrams(i);
			if (g == null) continue;
			withGrams++;
			if (i.grams() == null) converted++;
			Match m = matches.get(i.name());
			if (m != null && m.grade() != Grade.UNMATCHED) computable++;
		}
		long needConv = items.stream().filter(i -> i.status() == Status.NEEDS_CONVERSION || i.status() == Status.ML).count();
		md.append("\n## 계산 가능 범위\n\n| 항목 | 개수 | 비율 |\n| --- | ---: | ---: |\n");
		md.append("| g 확정 (원문 g + 환산) | ").append(withGrams).append(" | ").append(pct(withGrams, items.size())).append(" |\n");
		md.append("| 그중 환산으로 확정 | ").append(converted).append(" | 환산 필요 ").append(needConv).append("개 중 ").append(pct(converted, needConv)).append(" |\n");
		md.append("| g 확정 + 식품 매칭 (영양 계산 가능) | ").append(computable).append(" | ").append(pct(computable, items.size())).append(" |\n");

		md.append("\n## 미매칭 상위 50 (수동 지정 필요)\n\n");
		table(md, uses, matches, Grade.UNMATCHED, 50);
		md.append("\n## 추정 매칭(SIMILAR) 상위 50 (확인 필요)\n\n");
		table(md, uses, matches, Grade.SIMILAR, 50);

		Path out = Files.createDirectories(Path.of("data", "report")).resolve("matching-stats.md");
		Files.writeString(out, md);
		System.out.println("매칭 리포트: " + out.toAbsolutePath());
		System.out.println("  g 확정 " + pct(withGrams, items.size()) + ", 영양 계산 가능 " + pct(computable, items.size()));
	}

	private static void table(StringBuilder md, Map<String, Long> uses, Map<String, Match> matches, Grade grade, int limit) {
		md.append("| 재료명 | 빈도 | 규칙 | 선택된 식품 |\n| --- | ---: | --- | --- |\n");
		uses.entrySet().stream().filter(e -> matches.get(e.getKey()).grade() == grade)
				.sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
				.limit(limit).forEach(e -> {
					Match m = matches.get(e.getKey());
					md.append("| ").append(e.getKey().replace("|", "/")).append(" | ").append(e.getValue()).append(" | ").append(m.rule())
							.append(" | ").append(m.foodName() == null ? "" : m.foodCode() + " " + m.foodName()).append(" |\n");
				});
	}

	private static String nz(String s) {
		return s == null ? "" : s;
	}

	private static String pct(long n, long total) {
		return String.format("%.1f%%", total == 0 ? 0 : 100.0 * n / total);
	}
}
