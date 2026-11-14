package com.springboot.service;

import com.springboot.dto.MyRecipeForm;
import com.springboot.pipeline.IngredientAnalyzer;
import com.springboot.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.*;

/**
 * 회원의 개인 레시피. 공공 레시피와 같은 테이블에 owner_id를 붙여 저장하므로
 * 상세·영양 계산·감량/대체·맞춤 레시피 저장·CSV를 그대로 쓴다. 본인만 볼 수 있다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class MyRecipeService {

	public static final int MAX_INGREDIENT_LINES = 50;
	public static final int MAX_STEP_LINES = 30;

	private final JdbcTemplate jdbc;
	private final MemberRepository memberRepository;
	private final IngredientAnalyzer analyzer;

	public record Row(Long id, String name, String category, int totalItems, int computedItems) {
	}

	/** 저장 전 미리보기: 재료별 g·연결 식품 */
	@Transactional(readOnly = true)
	public List<IngredientAnalyzer.Line> preview(String ingredients) {
		return analyzer.analyze(ingredients);
	}

	public Long create(String username, MyRecipeForm form) {
		Long ownerId = memberRepository.findByUsername(username).orElseThrow().getId();
		String seq = "U" + UUID.randomUUID().toString().replace("-", "").substring(0, 15);
		var key = new GeneratedKeyHolder();
		jdbc.update(con -> {
			PreparedStatement ps = con.prepareStatement(
					"INSERT INTO recipes (seq, name, category, ingredient_text, owner_id) VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
			ps.setString(1, seq);
			ps.setString(2, form.getName().trim());
			ps.setString(3, blankToNull(form.getCategory()));
			ps.setString(4, normalize(form.getIngredients()));
			ps.setLong(5, ownerId);
			return ps;
		}, key);
		long id = Objects.requireNonNull(key.getKeys()).values().stream().filter(Number.class::isInstance)
				.map(v -> ((Number) v).longValue()).findFirst().orElseThrow();
		writeContent(id, form);
		return id;
	}

	public boolean update(String username, Long id, MyRecipeForm form) {
		if (!owns(username, id)) return false;
		jdbc.update("UPDATE recipes SET name = ?, category = ?, ingredient_text = ? WHERE id = ?",
				form.getName().trim(), blankToNull(form.getCategory()), normalize(form.getIngredients()), id);
		writeContent(id, form);
		return true;
	}

	public boolean delete(String username, Long id) {
		if (!owns(username, id)) return false;
		jdbc.update("DELETE FROM recipe_ingredients WHERE recipe_id = ?", id);
		jdbc.update("DELETE FROM recipe_steps WHERE recipe_id = ?", id);
		jdbc.update("DELETE FROM recipes WHERE id = ?", id);
		return true;
	}

	@Transactional(readOnly = true)
	public Optional<MyRecipeForm> form(String username, Long id) {
		if (!owns(username, id)) return Optional.empty();
		Map<String, Object> r = jdbc.queryForMap("SELECT name, category, ingredient_text FROM recipes WHERE id = ?", id);
		MyRecipeForm f = new MyRecipeForm();
		f.setName((String) r.get("name"));
		f.setCategory((String) r.get("category"));
		f.setIngredients((String) r.get("ingredient_text"));
		f.setSteps(String.join("\n", jdbc.queryForList("SELECT description FROM recipe_steps WHERE recipe_id = ? ORDER BY step_no", String.class, id)));
		return Optional.of(f);
	}

	@Transactional(readOnly = true)
	public List<Row> list(String username) {
		return jdbc.query("""
						SELECT r.id, r.name, r.category,
						       SUM(CASE WHEN ri.parse_status <> 'NON_FOOD' THEN 1 ELSE 0 END) AS total,
						       SUM(CASE WHEN ri.parse_status <> 'NON_FOOD' AND ri.grams IS NOT NULL AND ri.food_id IS NOT NULL
						                 AND ri.match_grade <> 'UNMATCHED' THEN 1 ELSE 0 END) AS computed
						FROM recipes r JOIN users u ON u.id = r.owner_id
						LEFT JOIN recipe_ingredients ri ON ri.recipe_id = r.id
						WHERE u.username = ?
						GROUP BY r.id, r.name, r.category ORDER BY r.id DESC""",
				(rs, i) -> new Row(rs.getLong("id"), rs.getString("name"), rs.getString("category"), rs.getInt("total"), rs.getInt("computed")),
				username);
	}

	/** 공공 레시피는 누구나, 개인 레시피는 주인만 */
	@Transactional(readOnly = true)
	public boolean canView(Long recipeId, String username) {
		List<String> owner = jdbc.queryForList(
				"SELECT u.username FROM recipes r LEFT JOIN users u ON u.id = r.owner_id WHERE r.id = ?", String.class, recipeId);
		if (owner.isEmpty()) return true; // 없는 레시피: 각 화면이 404로 처리
		return owner.getFirst() == null || owner.getFirst().equals(username);
	}

	@Transactional(readOnly = true)
	public boolean owns(String username, Long recipeId) {
		Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM recipes r JOIN users u ON u.id = r.owner_id WHERE r.id = ? AND u.username = ?",
				Integer.class, recipeId, username);
		return n != null && n > 0;
	}

	private void writeContent(long id, MyRecipeForm form) {
		analyzer.replaceIngredients(id, normalize(form.getIngredients()));
		jdbc.update("DELETE FROM recipe_steps WHERE recipe_id = ?", id);
		List<Object[]> steps = new ArrayList<>();
		for (String line : lines(form.getSteps(), MAX_STEP_LINES)) {
			// "1. 양파를 썬다" → 화면이 번호를 붙이므로 앞 번호는 뗀다
			steps.add(new Object[]{id, steps.size() + 1, line.replaceFirst("^\\d+\\s*[.)]\\s*", "")});
		}
		jdbc.batchUpdate("INSERT INTO recipe_steps (recipe_id, step_no, description) VALUES (?, ?, ?)", steps);
	}

	/** 재료는 한 줄에 하나: 빈 줄을 빼고 최대 줄 수까지 */
	static String normalize(String ingredients) {
		return String.join("\n", lines(ingredients, MAX_INGREDIENT_LINES));
	}

	private static List<String> lines(String text, int max) {
		if (text == null) return List.of();
		return text.lines().map(String::trim).filter(s -> !s.isEmpty()).limit(max).toList();
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.trim();
	}
}
