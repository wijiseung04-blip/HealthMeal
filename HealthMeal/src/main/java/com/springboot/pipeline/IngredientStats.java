package com.springboot.pipeline;

import com.springboot.pipeline.ParsedIngredient.Status;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 레시피 재료 문자열 통계. 파서 범위와 단위 환산표 우선순위를 정하는 근거 자료.
 * 결과: data/report/ingredient-stats.md, data/parsed/recipe-ingredients.csv
 */
final class IngredientStats {

	static void run() throws IOException {
		List<JsonNode> rows = RecipeRaw.load(Collector.RAW.resolve("cookrcp01"));
		List<String[]> csv = new ArrayList<>();
		List<ParsedIngredient> all = new ArrayList<>();
		int fullyWeighed = 0;
		for (JsonNode r : rows) {
			List<ParsedIngredient> items = IngredientParser.parse(r.path("RCP_PARTS_DTLS").asString(""));
			all.addAll(items);
			if (!items.isEmpty() && items.stream().allMatch(i -> i.grams() != null)) fullyWeighed++;
			for (ParsedIngredient i : items) {
				csv.add(new String[]{r.path("RCP_SEQ").asString(""), r.path("RCP_NM").asString(""), i.section(), i.raw(), i.name(),
						i.note(), i.amount() == null ? null : fmt(i.amount()), i.unit(), i.grams() == null ? null : fmt(i.grams()), i.status().name()});
			}
		}
		writeCsv(csv);

		StringBuilder md = new StringBuilder("# 레시피 재료 문자열 통계\n\n");
		md.append("- 레시피 ").append(rows.size()).append("건, 재료 항목 ").append(all.size()).append("개\n");
		md.append("- 모든 재료의 g 중량이 확정된 레시피: ").append(fullyWeighed).append("건 (").append(pct(fullyWeighed, rows.size())).append(")\n\n");

		md.append("## 상태별 항목 수\n\n| 상태 | 항목 수 | 비율 |\n| --- | ---: | ---: |\n");
		Map<Status, Long> byStatus = all.stream().collect(Collectors.groupingBy(ParsedIngredient::status, () -> new EnumMap<>(Status.class), Collectors.counting()));
		byStatus.forEach((s, n) -> md.append("| ").append(s).append(" | ").append(n).append(" | ").append(pct(n, all.size())).append(" |\n"));

		md.append("\n## 단위 빈도\n\n");
		table(md, count(all.stream().filter(i -> i.unit() != null).toList(), ParsedIngredient::unit), 30, "단위");

		md.append("\n## 환산이 필요한 재료×단위 상위 50 (unit_conversions 우선순위)\n\n");
		table(md, count(all.stream().filter(i -> i.status() == Status.NEEDS_CONVERSION || i.status() == Status.ML).toList(),
				i -> i.name() + " / " + i.unit()), 50, "재료 / 단위");

		md.append("\n## 재료명 상위 100\n\n");
		table(md, count(all, ParsedIngredient::name), 100, "재료명");

		md.append("\n## 소제목 상위 30\n\n");
		table(md, count(all.stream().filter(i -> i.section() != null).toList(), ParsedIngredient::section), 30, "소제목");

		md.append("\n## 수량 없음(NO_AMOUNT) 원문 상위 50\n\n");
		table(md, count(all.stream().filter(i -> i.status() == Status.NO_AMOUNT).toList(), ParsedIngredient::raw), 50, "원문");

		Path out = Files.createDirectories(Path.of("data", "report")).resolve("ingredient-stats.md");
		Files.writeString(out, md);
		System.out.println("통계: " + out.toAbsolutePath());
		byStatus.forEach((s, n) -> System.out.println("  " + s + " " + n + " (" + pct(n, all.size()) + ")"));
	}

	private static Map<String, Long> count(List<ParsedIngredient> items, Function<ParsedIngredient, String> key) {
		return items.stream().collect(Collectors.groupingBy(key, Collectors.counting()));
	}

	private static void table(StringBuilder md, Map<String, Long> counts, int limit, String header) {
		md.append("| ").append(header).append(" | 빈도 |\n| --- | ---: |\n");
		counts.entrySet().stream()
				.sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
				.limit(limit)
				.forEach(e -> md.append("| ").append(e.getKey().replace("|", "\\|").replace("\n", " ")).append(" | ").append(e.getValue()).append(" |\n"));
	}

	private static void writeCsv(List<String[]> rows) throws IOException {
		Path out = Files.createDirectories(Path.of("data", "parsed")).resolve("recipe-ingredients.csv");
		StringBuilder sb = new StringBuilder("\uFEFFrecipe_seq,recipe_name,section,raw,name,note,amount,unit,grams,status\n"); // BOM: 엑셀 한글
		for (String[] r : rows) {
			sb.append(Arrays.stream(r).map(IngredientStats::csvCell).collect(Collectors.joining(","))).append('\n');
		}
		Files.writeString(out, sb);
		System.out.println("파싱 결과: " + out.toAbsolutePath());
	}

	private static String csvCell(String v) {
		if (v == null) return "";
		return "\"" + v.replace("\"", "\"\"").replace("\n", " ") + "\"";
	}

	private static String fmt(double d) {
		return d == Math.rint(d) ? String.valueOf((long) d) : String.format("%.3f", d).replaceAll("0+$", "");
	}

	private static String pct(long n, long total) {
		return String.format("%.1f%%", total == 0 ? 0 : 100.0 * n / total);
	}
}
