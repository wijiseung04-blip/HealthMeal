package com.springboot.service;

import com.springboot.domain.NutritionGoal;
import com.springboot.nutrition.NutritionEngine.Comparison;
import com.springboot.service.RecipeService.RecipeDetail;
import com.springboot.service.SubstitutionService.Applied;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 레시피(또는 감량·대체를 적용한 맞춤 레시피)를 엑셀에서 열 수 있는 CSV로 만든다 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecipeExportService {

	private final RecipeService recipeService;
	private final SubstitutionService substitutionService;

	public record Export(String fileName, byte[] content) {
	}

	public Optional<Export> export(Long id, NutritionGoal goal, String option) {
		Optional<RecipeDetail> found = recipeService.detail(id);
		if (found.isEmpty()) return Optional.empty();
		RecipeDetail r = found.get();
		Applied applied = goal == null || option == null ? null : substitutionService.apply(id, goal, option).orElse(null);

		List<List<String>> rows = new ArrayList<>();
		rows.add(List.of("레시피", r.name()));
		rows.add(List.of("종류", nz(r.category()) + " / " + nz(r.cookingMethod())));
		if (applied != null) {
			rows.add(List.of("영양 목표", goal.getLabel()));
			rows.add(List.of("적용한 변경", applied.option().ingredient() + " → " + applied.option().description()));
			if (applied.option().caution() != null) rows.add(List.of("조리 주의", applied.option().caution()));
		}
		rows.add(List.of());

		rows.add(List.of("재료", "계산 중량(g)", "연결 식품"));
		if (applied != null) {
			applied.afterLines().forEach(l -> rows.add(List.of(l.rawText(), num(l.grams()), nz(l.foodName()))));
		} else {
			r.ingredients().forEach(i -> rows.add(List.of(i.rawText(), num(i.grams()), nz(i.foodName()))));
		}
		rows.add(List.of());

		if (applied != null) {
			rows.add(List.of("영양소", "변경 전", "변경 후", "변화량", "변화율"));
			for (Comparison c : applied.comparisons()) {
				String rate = c.rate() == null ? "" : (c.rateKind().name().equals("UPPER_BOUND") ? "최대 " : "") + num(c.rate() * 100) + "%";
				rows.add(List.of(c.nutrient(), num(c.before()), num(c.after()), c.delta() == null ? "계산 불가" : num(c.delta()), rate));
			}
		} else {
			rows.add(List.of("영양소", "합계", "단위", "계산 범위"));
			r.nutrients().forEach(n -> rows.add(List.of(n.name(), num(n.value()), n.unit(), n.coverage().name())));
		}
		if (r.partial()) {
			rows.add(List.of("참고", "계산에서 빠진 재료가 있어 실제보다 낮을 수 있음: " + String.join(", ", r.unknownItems())));
		}
		rows.add(List.of("출처", "레시피: 식약처 COOKRCP01 / 영양성분: 국가표준식품성분, 100g당 값 × 사용량"));
		rows.add(List.of());

		rows.add(List.of("조리 순서"));
		r.steps().forEach(s -> rows.add(List.of(String.valueOf(s.no()), s.description())));

		StringBuilder sb = new StringBuilder("﻿"); // 엑셀에서 한글이 깨지지 않도록 BOM
		for (List<String> row : rows) {
			sb.append(String.join(",", row.stream().map(RecipeExportService::cell).toList())).append("\r\n");
		}
		String name = r.name() + (applied != null ? "_" + goal.getLabel() : "") + ".csv";
		return Optional.of(new Export(name, sb.toString().getBytes(StandardCharsets.UTF_8)));
	}

	private static String cell(String v) {
		return "\"" + nz(v).replace("\"", "\"\"").replace("\n", " ") + "\"";
	}

	private static String num(Double d) {
		return d == null ? "" : String.format("%.1f", d);
	}

	private static String nz(String s) {
		return s == null ? "" : s;
	}
}
