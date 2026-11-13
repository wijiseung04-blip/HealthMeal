package com.springboot.service;

import com.springboot.nutrition.NutritionEngine;
import com.springboot.nutrition.NutritionEngine.Item;
import com.springboot.nutrition.NutritionEngine.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** DB의 레시피 재료와 식품 영양성분으로 계산 엔진을 실행한다 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecipeNutritionService {

	static final String WATER_CODE = "WATER";
	private final JdbcTemplate jdbc;

	public record IngredientLine(String rawText, String name, Double grams, String gramsSource, String foodCode, String foodName,
								 String matchGrade) {
	}

	public record RecipeNutrition(Long recipeId, String recipeName, List<IngredientLine> ingredients, Result result) {
	}

	public Optional<RecipeNutrition> calculate(Long recipeId) {
		List<String> name = jdbc.queryForList("SELECT name FROM recipes WHERE id = ?", String.class, recipeId);
		if (name.isEmpty()) return Optional.empty();

		// 조리 도구(NON_FOOD)는 계산 대상이 아니다. 미매칭이면 식품 없음으로 넘긴다
		List<IngredientLine> lines = jdbc.query("""
				SELECT ri.raw_text, ri.name, ri.grams, ri.grams_source, f.code, f.name AS food_name, ri.match_grade
				FROM recipe_ingredients ri LEFT JOIN foods f ON f.id = ri.food_id
				WHERE ri.recipe_id = ? AND ri.parse_status <> 'NON_FOOD'
				ORDER BY ri.position""",
				(rs, i) -> new IngredientLine(rs.getString(1), rs.getString(2), (Double) rs.getObject(3), rs.getString(4),
						"UNMATCHED".equals(rs.getString(7)) ? null : rs.getString(5), rs.getString(6), rs.getString(7)),
				recipeId);

		List<Item> items = lines.stream().map(l -> new Item(l.rawText(), l.foodCode(), l.grams())).toList();
		Result result = NutritionEngine.calculate(items, NutritionEngine.KEY_NUTRIENTS, per100g(lines));
		return Optional.of(new RecipeNutrition(recipeId, name.getFirst(), lines, result));
	}

	/** 필요한 식품의 주요 영양소만 한 번에 읽는다. 물은 0 */
	private NutritionEngine.NutrientSource per100g(List<IngredientLine> lines) {
		Set<String> codes = new HashSet<>();
		lines.forEach(l -> {
			if (l.foodCode() != null) codes.add(l.foodCode());
		});
		Map<String, Map<String, Double>> values = new HashMap<>();
		if (!codes.isEmpty()) {
			String in = String.join(",", Collections.nCopies(codes.size(), "?"));
			String nIn = String.join(",", Collections.nCopies(NutritionEngine.KEY_NUTRIENTS.size(), "?"));
			List<Object> params = new ArrayList<>(codes);
			params.addAll(NutritionEngine.KEY_NUTRIENTS);
			jdbc.query("SELECT f.code, fn.nutrient_code, fn.content FROM food_nutrients fn JOIN foods f ON f.id = fn.food_id "
							+ "WHERE f.code IN (" + in + ") AND fn.nutrient_code IN (" + nIn + ")",
					rs -> {
						values.computeIfAbsent(rs.getString(1), k -> new HashMap<>()).put(rs.getString(2), (Double) rs.getObject(3));
					}, params.toArray());
		}
		Map<String, Double> water = new HashMap<>();
		NutritionEngine.KEY_NUTRIENTS.forEach(k -> water.put(k, 0.0));
		values.put(WATER_CODE, water);
		return values::get;
	}
}
