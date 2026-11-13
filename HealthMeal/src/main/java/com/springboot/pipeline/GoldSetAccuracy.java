package com.springboot.pipeline;

import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 사람이 확인한 정답셋(data/goldset/recipe-ingredients-gold.csv)과 파서 출력을 비교한다.
 * 레시피마다 재료 순서대로 맞춰 보고, 항목별·필드별 정확도를 낸다.
 */
final class GoldSetAccuracy {

	static final Path GOLD = Path.of("data", "goldset", "recipe-ingredients-gold.csv");
	static final List<String> FIELDS = List.of("section", "name", "amount", "unit", "grams", "status");

	record Mismatch(String recipeSeq, String raw, String field, String expected, String actual) {
	}

	record Result(int recipes, int expectedItems, int parsedItems, int correctItems, Map<String, Integer> fieldCorrect, List<Mismatch> mismatches) {

		double itemAccuracy() {
			return 100.0 * correctItems / expectedItems;
		}
	}

	static Result evaluate() throws IOException {
		Map<String, List<List<String>>> gold = new LinkedHashMap<>();
		for (List<String> r : Csv.read(GOLD)) gold.computeIfAbsent(r.get(0), k -> new ArrayList<>()).add(r);
		Map<String, String> source = new HashMap<>();
		for (JsonNode r : RecipeRaw.load(Collector.RAW.resolve("cookrcp01"))) {
			source.put(r.path("RCP_SEQ").asString(""), r.path("RCP_PARTS_DTLS").asString(""));
		}

		int expected = 0, parsedCount = 0, correct = 0;
		Map<String, Integer> fieldCorrect = new LinkedHashMap<>();
		FIELDS.forEach(f -> fieldCorrect.put(f, 0));
		List<Mismatch> mismatches = new ArrayList<>();
		for (var e : gold.entrySet()) {
			List<ParsedIngredient> parsed = IngredientParser.parse(source.get(e.getKey()));
			parsedCount += parsed.size();
			List<List<String>> rows = e.getValue();
			expected += rows.size();
			for (int i = 0; i < rows.size(); i++) {
				List<String> g = rows.get(i);
				ParsedIngredient p = i < parsed.size() ? parsed.get(i) : null;
				// 열: recipe_seq,category,recipe_name,raw,section,name,amount,unit,grams,status
				String[] exp = {g.get(4), g.get(5), g.get(6), g.get(7), g.get(8), g.get(9)};
				String[] act = p == null ? new String[6] : new String[]{p.section(), p.name(), num(p.amount()), p.unit(), num(p.grams()), p.status().name()};
				boolean allOk = true;
				for (int f = 0; f < 6; f++) {
					if (same(exp[f], act[f])) {
						fieldCorrect.merge(FIELDS.get(f), 1, Integer::sum);
					} else {
						allOk = false;
						mismatches.add(new Mismatch(e.getKey(), g.get(3), FIELDS.get(f), exp[f], act[f]));
					}
				}
				if (allOk) correct++;
			}
		}
		return new Result(gold.size(), expected, parsedCount, correct, fieldCorrect, mismatches);
	}

	static void report() throws IOException {
		Result r = evaluate();
		StringBuilder md = new StringBuilder("# 재료 파싱 정확도 (정답셋)\n\n");
		md.append("- 레시피 ").append(r.recipes()).append("개, 정답 재료 ").append(r.expectedItems()).append("개, 파서 출력 ").append(r.parsedItems()).append("개\n");
		md.append("- **항목 정확도 (6개 필드 모두 일치): ").append(r.correctItems()).append("/").append(r.expectedItems())
				.append(" = ").append(String.format("%.1f%%", r.itemAccuracy())).append("**\n\n");
		md.append("| 필드 | 일치 | 정확도 |\n| --- | ---: | ---: |\n");
		r.fieldCorrect().forEach((f, n) -> md.append("| ").append(f).append(" | ").append(n).append(" | ")
				.append(String.format("%.1f%%", 100.0 * n / r.expectedItems())).append(" |\n"));
		md.append("\n## 불일치\n\n| 레시피 | 원문 | 필드 | 정답 | 파서 |\n| --- | --- | --- | --- | --- |\n");
		for (Mismatch m : r.mismatches()) {
			md.append("| ").append(m.recipeSeq()).append(" | ").append(m.raw()).append(" | ").append(m.field()).append(" | ")
					.append(nz(m.expected())).append(" | ").append(nz(m.actual())).append(" |\n");
		}
		Path out = Files.createDirectories(Path.of("data", "report")).resolve("goldset-accuracy.md");
		Files.writeString(out, md);
		System.out.println("정답셋 정확도: " + String.format("%.1f%%", r.itemAccuracy()) + " → " + out.toAbsolutePath());
	}

	private static boolean same(String expected, String actual) {
		String a = nz(expected), b = nz(actual);
		if (a.equals(b)) return true;
		try {
			return Math.abs(Double.parseDouble(a) - Double.parseDouble(b)) < 1e-3;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	private static String num(Double d) {
		return d == null ? null : Csv.num(d);
	}

	private static String nz(String s) {
		return s == null ? "" : s;
	}
}
