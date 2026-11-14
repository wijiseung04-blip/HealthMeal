package com.springboot.pipeline;

import com.springboot.pipeline.FoodMatcher.Match;
import com.springboot.pipeline.IngredientResolver.Resolved;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.sql.Types;
import java.util.*;

/**
 * 웹에서 회원이 입력한 재료 문장을 공공 레시피와 같은 규칙으로 분석해 recipe_ingredients에 넣는다.
 * 파싱 → g 확정(원문·환산표·확인된 개당 중량) → 식품 매칭(DB의 식품 + 수동 매핑).
 * 환산표는 match 단계가 만든 data/mapping/unit-conversions.csv를 쓴다.
 */
@Component
@RequiredArgsConstructor
public class IngredientAnalyzer {

	private final JdbcTemplate jdbc;
	private volatile State state;

	private record State(IngredientResolver resolver, Map<String, Long> foodIds) {
	}

	/** 식품을 다시 적재했으면 새로 만든다 (load-recipes가 부른다) */
	public void reset() {
		state = null;
	}

	private State state() {
		State s = state;
		if (s != null) return s;
		synchronized (this) {
			if (state != null) return state;
			try {
				Map<String, Long> ids = new HashMap<>();
				List<FoodRecord> foods = new ArrayList<>();
				jdbc.query("SELECT id, code, name FROM foods", rs -> {
					ids.put(rs.getString("code"), rs.getLong("id"));
					foods.add(new FoodRecord(rs.getString("code"), rs.getString("name"), null, null, null, List.of()));
				});
				FoodMatcher matcher = new FoodMatcher(foods, IngredientMatching.readManual());
				UnitConversions conversions = UnitConversions.fromCsv(IngredientMatching.MAPPING.resolve("unit-conversions.csv"));
				// 같은 식품 밀도 공유·확인된 개당 중량: 환산표에 있는 재료명의 매칭으로 연결
				Map<String, Match> matches = new HashMap<>();
				conversions.entries().forEach(e -> matches.computeIfAbsent(e.name(), matcher::match));
				IngredientMatching.configure(conversions, matches);
				state = new State(new IngredientResolver(matcher, conversions), ids);
				return state;
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
	}

	/** 분석 결과 한 줄 (화면 미리보기·저장 공용) */
	public record Line(int position, String raw, String name, Double grams, String gramsSource, String parseStatus,
					   String foodName, String matchGrade) {
		public boolean computable() {
			return grams != null && matchGrade != null && !matchGrade.equals("UNMATCHED");
		}
	}

	public List<Line> analyze(String ingredientText) {
		return resolve(ingredientText).stream().map(x -> new Line(x.position(), x.parsed().raw(), x.parsed().name(), x.grams(),
				x.gramsSource().name(), x.parsed().status().name(), x.match() == null ? null : x.match().foodName(),
				x.match() == null ? null : x.match().grade().name())).toList();
	}

	/** 레시피의 재료를 지우고 새로 분석해 넣는다 */
	public void replaceIngredients(long recipeId, String ingredientText) {
		jdbc.update("DELETE FROM recipe_ingredients WHERE recipe_id = ?", recipeId);
		Map<String, Long> foodIds = state().foodIds();
		List<Object[]> rows = new ArrayList<>();
		for (Resolved x : resolve(ingredientText)) rows.add(row(recipeId, x, foodIds));
		insertIngredients(jdbc, rows);
	}

	/** 개인 레시피 전체를 다시 분석한다 (식품·매핑이 바뀐 뒤) */
	public int reanalyzeOwned() {
		reset();
		List<Map<String, Object>> owned = jdbc.queryForList("SELECT id, ingredient_text FROM recipes WHERE owner_id IS NOT NULL");
		for (Map<String, Object> r : owned) {
			replaceIngredients(((Number) r.get("id")).longValue(), Objects.toString(r.get("ingredient_text"), ""));
		}
		return owned.size();
	}

	private List<Resolved> resolve(String ingredientText) {
		return state().resolver().resolve(IngredientParser.parse(ingredientText == null ? "" : ingredientText));
	}

	static Object[] row(long recipeId, Resolved x, Map<String, Long> foodIds) {
		var p = x.parsed();
		var m = x.match();
		Long foodId = m == null || m.foodCode() == null ? null : foodIds.get(m.foodCode());
		return new Object[]{recipeId, x.position(), p.section(), cut(p.raw(), 500), cut(p.name(), 200), cut(p.note(), 300),
				p.amount(), p.unit(), x.grams(), x.gramsSource().name(), p.status().name(), foodId,
				m == null ? null : m.grade().name(), m == null ? null : cut(m.rule(), 50)};
	}

	static void insertIngredients(JdbcTemplate jdbc, List<Object[]> rows) {
		int[] types = {Types.BIGINT, Types.INTEGER, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.DOUBLE, Types.VARCHAR,
				Types.DOUBLE, Types.VARCHAR, Types.VARCHAR, Types.BIGINT, Types.VARCHAR, Types.VARCHAR};
		jdbc.batchUpdate("INSERT INTO recipe_ingredients (recipe_id, position, section, raw_text, name, note, amount, unit, grams, grams_source, "
				+ "parse_status, food_id, match_grade, match_rule) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", rows, 2000,
				(ps, row) -> {
					for (int i = 0; i < row.length; i++) {
						if (row[i] == null) ps.setNull(i + 1, types[i]);
						else ps.setObject(i + 1, row[i], types[i]);
					}
				});
	}

	private static String cut(String v, int max) {
		return v == null || v.length() <= max ? v : v.substring(0, max);
	}
}
