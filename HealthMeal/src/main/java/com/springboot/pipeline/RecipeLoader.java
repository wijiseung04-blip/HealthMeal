package com.springboot.pipeline;

import com.springboot.pipeline.IngredientResolver.RecipeData;
import com.springboot.pipeline.IngredientResolver.Resolved;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 레시피·조리 순서·재료(파싱·중량·매칭 결과)를 적재한다. 식품(load-foods)을 먼저 적재해야 한다.
 * 다시 실행하면 기존 레시피 데이터를 지우고 새로 넣는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecipeLoader {

	private final JdbcTemplate jdbc;

	@Transactional
	public int load() throws IOException {
		Map<String, Long> foodIds = new HashMap<>();
		jdbc.query("SELECT id, code FROM foods", rs -> {
			foodIds.put(rs.getString("code"), rs.getLong("id"));
		});
		if (foodIds.isEmpty()) throw new IllegalStateException("foods 테이블이 비어 있습니다. 먼저 load-foods를 실행하세요.");

		List<RecipeData> recipes = IngredientResolver.resolveAll(FoodSources.loadAll());

		jdbc.update("DELETE FROM recipe_ingredients");
		jdbc.update("DELETE FROM recipe_steps");
		jdbc.update("DELETE FROM recipes");

		List<Object[]> recipeRows = new ArrayList<>();
		for (RecipeData d : recipes) {
			JsonNode r = d.raw();
			recipeRows.add(new Object[]{s(r, "RCP_SEQ"), s(r, "RCP_NM"), s(r, "RCP_PAT2"), s(r, "RCP_WAY2"), s(r, "ATT_FILE_NO_MAIN"),
					s(r, "HASH_TAG"), s(r, "RCP_NA_TIP"), s(r, "RCP_PARTS_DTLS"),
					num(r, "INFO_ENG"), num(r, "INFO_CAR"), num(r, "INFO_PRO"), num(r, "INFO_FAT"), num(r, "INFO_NA")});
		}
		jdbc.batchUpdate("INSERT INTO recipes (seq, name, category, cooking_method, main_image, hash_tag, sodium_tip, ingredient_text, "
				+ "info_energy, info_carbohydrate, info_protein, info_fat, info_sodium) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", recipeRows);

		Map<String, Long> recipeIds = new HashMap<>();
		jdbc.query("SELECT id, seq FROM recipes", rs -> {
			recipeIds.put(rs.getString("seq"), rs.getLong("id"));
		});

		List<Object[]> steps = new ArrayList<>();
		List<Object[]> ingredients = new ArrayList<>();
		for (RecipeData d : recipes) {
			Long recipeId = recipeIds.get(s(d.raw(), "RCP_SEQ"));
			int stepNo = 0;
			for (int i = 1; i <= 20; i++) {
				String desc = s(d.raw(), "MANUAL%02d".formatted(i));
				if (desc == null) continue; // 빈 조리 단계는 건너뛴다
				steps.add(new Object[]{recipeId, ++stepNo, desc.replace("\n", " ").trim(), s(d.raw(), "MANUAL_IMG%02d".formatted(i))});
			}
			for (Resolved x : d.ingredients()) {
				var p = x.parsed();
				var m = x.match();
				Long foodId = m == null || m.foodCode() == null ? null : foodIds.get(m.foodCode());
				ingredients.add(new Object[]{recipeId, x.position(), p.section(), cut(p.raw(), 500), cut(p.name(), 200), cut(p.note(), 300),
						p.amount(), p.unit(), x.grams(), x.gramsSource().name(), p.status().name(), foodId,
						m == null ? null : m.grade().name(), m == null ? null : cut(m.rule(), 50)});
			}
		}
		jdbc.batchUpdate("INSERT INTO recipe_steps (recipe_id, step_no, description, image) VALUES (?, ?, ?, ?)", steps);
		int[] types = {Types.BIGINT, Types.INTEGER, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.DOUBLE, Types.VARCHAR,
				Types.DOUBLE, Types.VARCHAR, Types.VARCHAR, Types.BIGINT, Types.VARCHAR, Types.VARCHAR};
		jdbc.batchUpdate("INSERT INTO recipe_ingredients (recipe_id, position, section, raw_text, name, note, amount, unit, grams, grams_source, "
				+ "parse_status, food_id, match_grade, match_rule) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", ingredients, 2000,
				(ps, row) -> {
					for (int i = 0; i < row.length; i++) {
						if (row[i] == null) ps.setNull(i + 1, types[i]);
						else ps.setObject(i + 1, row[i], types[i]);
					}
				});

		int pool = loadSubstitutePool(foodIds.keySet());

		jdbc.update("INSERT INTO data_versions (source, version, record_count, loaded_at) VALUES (?, ?, ?, ?)",
				"COOKRCP01", LocalDate.now().toString(), recipes.size(), Timestamp.valueOf(LocalDateTime.now()));
		log.info("레시피 적재 완료: 레시피 {}개, 조리 단계 {}개, 재료 {}개, 대체 후보 규칙 {}개", recipes.size(), steps.size(), ingredients.size(), pool);
		return recipes.size();
	}

	/** data/mapping/substitute-pool.csv → substitute_pool. confirmed 칸이 O인 규칙만 추천에 쓴다 */
	private int loadSubstitutePool(Set<String> foodCodes) throws IOException {
		jdbc.update("DELETE FROM substitute_pool");
		List<Object[]> rows = new ArrayList<>();
		for (List<String> r : Csv.read(Path.of("data", "mapping", "substitute-pool.csv"))) {
			if (r.size() < 7 || !foodCodes.contains(r.get(0)) || !foodCodes.contains(r.get(1))) {
				log.warn("대체 후보 규칙 건너뜀 (식품 코드 없음): {}", r);
				continue;
			}
			rows.add(new Object[]{r.get(0), r.get(1), Double.parseDouble(r.get(2)), Double.parseDouble(r.get(3)), r.get(4), r.get(5),
					"O".equalsIgnoreCase(r.get(6).trim()), r.size() > 7 ? cut(r.get(7), 300) : null});
		}
		jdbc.batchUpdate("INSERT INTO substitute_pool (source_food_code, candidate_food_code, min_ratio, max_ratio, culinary_role, caution, confirmed, comment) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", rows);
		return rows.size();
	}

	private static String s(JsonNode r, String field) {
		String v = r.path(field).asString("").trim();
		return v.isEmpty() ? null : v;
	}

	private static Double num(JsonNode r, String field) {
		String v = s(r, field);
		try {
			return v == null ? null : Double.valueOf(v);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static String cut(String v, int max) {
		return v == null || v.length() <= max ? v : v.substring(0, max);
	}
}
