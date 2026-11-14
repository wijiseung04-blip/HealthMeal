package com.springboot.pipeline;

import com.springboot.nutrition.IngredientAnalysis;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

/** 레시피 재료마다 역할 2축(조리·영양)을 규칙으로 분석해 ingredient_analysis에 저장한다 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IngredientAnalysisWriter {

	private static final List<String> NUTRIENTS = List.of("단백질", "지방", "탄수화물", "나트륨", "총 식이섬유");
	private final JdbcTemplate jdbc;

	/** 전체 레시피 (load-recipes 끝에) */
	public int analyzeAll() {
		jdbc.update("DELETE FROM ingredient_analysis");
		int n = write(null);
		log.info("재료 역할 분석 저장: {}줄", n);
		return n;
	}

	/** 레시피 하나 (개인 레시피 저장·수정 때) */
	public int analyzeRecipe(long recipeId) {
		jdbc.update("DELETE FROM ingredient_analysis WHERE recipe_id = ?", recipeId);
		return write(recipeId);
	}

	private int write(Long recipeId) {
		String where = recipeId == null ? "" : " AND ri.recipe_id = " + recipeId;
		record Row(long id, long recipeId, String name, String code, String group, Double grams, Long foodId) {
		}
		List<Row> rows = jdbc.query("""
						SELECT ri.id, ri.recipe_id, ri.name, f.code, f.group_name, ri.grams, f.id
						FROM recipe_ingredients ri LEFT JOIN foods f ON f.id = ri.food_id
						WHERE ri.parse_status <> 'NON_FOOD' AND (ri.match_grade IS NULL OR ri.match_grade <> 'UNMATCHED')""" + where + " ORDER BY ri.recipe_id, ri.position",
				(rs, i) -> new Row(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
						(Double) rs.getObject(6), (Long) rs.getObject(7)));
		Map<Long, List<String>> steps = new HashMap<>();
		jdbc.query("SELECT recipe_id, description FROM recipe_steps" + (recipeId == null ? "" : " WHERE recipe_id = " + recipeId) + " ORDER BY recipe_id, step_no",
				rs -> {
					steps.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getString(2));
				});
		Map<Long, Map<String, Double>> per100 = new HashMap<>();
		jdbc.query("SELECT food_id, nutrient_code, content FROM food_nutrients WHERE content IS NOT NULL AND nutrient_code IN ("
						+ String.join(",", Collections.nCopies(NUTRIENTS.size(), "?")) + ")",
				rs -> {
					per100.computeIfAbsent(rs.getLong(1), k -> new HashMap<>()).put(rs.getString(2), rs.getDouble(3));
				}, NUTRIENTS.toArray());

		Timestamp now = Timestamp.valueOf(LocalDateTime.now());
		List<Object[]> out = new ArrayList<>();
		for (Row r : rows) {
			var a = IngredientAnalysis.analyze(r.name(), r.code(), r.group(), r.grams(),
					r.foodId() == null ? Map.of() : per100.getOrDefault(r.foodId(), Map.of()), steps.getOrDefault(r.recipeId(), List.of()));
			out.add(new Object[]{r.id(), r.recipeId(), a.culinary().name(), a.nutrition().name(), a.evidence(), "RULE", now});
		}
		jdbc.batchUpdate("INSERT INTO ingredient_analysis (recipe_ingredient_id, recipe_id, culinary_role, nutrition_role, evidence, method, analyzed_at) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?)", out);
		return out.size();
	}
}
