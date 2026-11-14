package com.springboot.pipeline;

import com.springboot.nutrition.IngredientRoleClassifier;
import com.springboot.nutrition.IngredientRoleClassifier.Classification;
import com.springboot.nutrition.IngredientRoleClassifier.Role;
import com.springboot.pipeline.FoodMatcher.Grade;
import com.springboot.pipeline.IngredientResolver.RecipeData;
import com.springboot.pipeline.IngredientResolver.Resolved;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 기름·설탕류 재료 역할 판단(규칙) 결과를 전체 레시피에 돌려 분포를 보고, 사람이 확인할 표본을 만든다.
 * 표본의 correct 열에 O/X를 적고 다시 실행하면 정확도를 계산한다.
 * 결과: data/report/ingredient-roles.md, data/goldset/ingredient-roles-check.csv
 */
final class RoleReport {

	static final Path CHECK = Path.of("data", "goldset", "ingredient-roles-check.csv");
	private static final int SAMPLE = 100;

	record Row(String seq, String recipe, String ingredient, String group, Classification c) {
	}

	static void run() throws IOException {
		List<FoodRecord> foods = FoodSources.loadAll();
		Map<String, String> groupByCode = new HashMap<>();
		foods.forEach(f -> groupByCode.put(f.code(), f.group()));

		List<Row> rows = new ArrayList<>();
		for (RecipeData d : IngredientResolver.resolveAll(foods)) {
			List<String> steps = steps(d.raw());
			for (Resolved x : d.ingredients()) {
				if (x.match() == null || x.match().grade() == Grade.UNMATCHED) continue;
				String group = groupByCode.get(x.match().foodCode());
				if (!IngredientRoleClassifier.applies(group)) continue;
				rows.add(new Row(d.raw().path("RCP_SEQ").asString(""), d.raw().path("RCP_NM").asString(""), x.parsed().name(), group,
						IngredientRoleClassifier.classify(x.parsed().name(), group, steps)));
			}
		}

		StringBuilder md = new StringBuilder("# 기름·설탕류 역할 판단 (조리 순서 규칙, AI 미사용)\n\n");
		md.append("- 대상: 유지류·당류로 매칭된 재료 ").append(rows.size()).append("개\n");
		long decided = rows.stream().filter(r -> r.c().role() != Role.UNKNOWN).count();
		long reducible = rows.stream().filter(r -> r.c().role().reducible()).count();
		md.append("- 역할 판단: ").append(decided).append("개 (").append(pct(decided, rows.size())).append(")\n");
		md.append("- 감량 후보로 쓰는 것(볶음·부침용 기름, 맛 내기): ").append(reducible).append("개 (").append(pct(reducible, rows.size())).append(")\n\n");
		md.append("| 역할 | 유지류 | 당류 | 감량 |\n| --- | ---: | ---: | --- |\n");
		for (Role role : Role.values()) {
			long oil = rows.stream().filter(r -> r.c().role() == role && r.group().equals(IngredientRoleClassifier.OIL_GROUP)).count();
			long sugar = rows.stream().filter(r -> r.c().role() == role && r.group().equals(IngredientRoleClassifier.SUGAR_GROUP)).count();
			md.append("| ").append(role.label()).append(" | ").append(oil).append(" | ").append(sugar).append(" | ")
					.append(role.reducible() ? "20·30%" : role == Role.UNKNOWN ? "검수 대기" : "하지 않음").append(" |\n");
		}
		md.append("\n").append(accuracy(rows));

		Path out = Files.createDirectories(Path.of("data", "report")).resolve("ingredient-roles.md");
		Files.writeString(out, md);
		System.out.println("역할 판단 " + pct(decided, rows.size()) + " → " + out.toAbsolutePath());
	}

	/** 사람이 확인한 표본이 있으면 정확도, 없으면 표본을 새로 만든다 */
	private static String accuracy(List<Row> rows) throws IOException {
		List<List<String>> checked = Csv.read(CHECK);
		long marked = checked.stream().filter(r -> r.size() > 6 && !r.get(6).isBlank()).count();
		if (marked > 0) {
			long correct = checked.stream().filter(r -> r.size() > 6 && r.get(6).trim().equalsIgnoreCase("O")).count();
			StringBuilder sb = new StringBuilder("## 사람 확인 결과\n\n- 확인 ").append(marked).append("건 중 맞음 ").append(correct)
					.append("건 (").append(pct(correct, marked)).append(")\n\n| 레시피 | 재료 | 판단 | 근거 |\n| --- | --- | --- | --- |\n");
			checked.stream().filter(r -> r.size() > 6 && r.get(6).trim().equalsIgnoreCase("X"))
					.forEach(r -> sb.append("| ").append(r.get(1)).append(" | ").append(r.get(2)).append(" | ").append(r.get(4))
							.append(" | ").append(r.get(5).replace("|", "/")).append(" |\n"));
			return sb.toString();
		}
		// 역할을 판단한 것 중 고르게 표본을 뽑는다 (매번 같은 결과)
		List<Row> decided = rows.stream().filter(r -> r.c().role() != Role.UNKNOWN).toList();
		List<List<Object>> sample = new ArrayList<>();
		for (int i = 0; i < SAMPLE && i < decided.size(); i++) {
			Row r = decided.get((int) ((long) i * decided.size() / Math.min(SAMPLE, decided.size())));
			sample.add(List.of(r.seq(), r.recipe(), r.ingredient(), r.group(), r.c().role().label(), r.c().evidence(), ""));
		}
		Files.createDirectories(CHECK.getParent());
		Csv.write(CHECK, List.of("recipe_seq", "recipe_name", "ingredient", "group", "role", "evidence", "correct"), sample);
		return "## 사람 확인\n\n`" + CHECK + "`의 `correct` 열에 맞으면 O, 틀리면 X를 적고 다시 실행하면 정확도를 계산한다.\n";
	}

	private static List<String> steps(JsonNode raw) {
		List<String> steps = new ArrayList<>();
		for (int i = 1; i <= 20; i++) {
			String s = raw.path("MANUAL%02d".formatted(i)).asString("");
			if (!s.isBlank()) steps.add(s);
		}
		return steps;
	}

	private static String pct(long n, long total) {
		return String.format("%.1f%%", total == 0 ? 0 : 100.0 * n / total);
	}
}
