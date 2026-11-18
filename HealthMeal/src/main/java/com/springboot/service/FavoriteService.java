package com.springboot.service;

import com.springboot.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 레시피 즐겨찾기 (회원별). 공공 레시피와 본인 개인 레시피만 */
@Service
@RequiredArgsConstructor
@Transactional
public class FavoriteService {

	private final JdbcTemplate jdbc;
	private final MemberRepository memberRepository;
	private final MyRecipeService myRecipeService;

	/** recipeId: 지금 레시피 id (다시 적재되면 같은 번호의 새 id), category: 목록 표시용 */
	public record Row(Long id, Long recipeId, String recipeName, String category, LocalDateTime createdAt) {
	}

	/** 즐겨찾기를 켜거나 끈다. 결과: 켜졌으면 true, 꺼졌으면 false, 볼 수 없는 레시피면 empty */
	public Optional<Boolean> toggle(String username, Long recipeId) {
		Optional<Map<String, Object>> recipe = recipe(username, recipeId);
		if (recipe.isEmpty()) return Optional.empty();
		Long memberId = memberRepository.findByUsername(username).orElseThrow().getId();
		String seq = (String) recipe.get().get("seq");
		int removed = jdbc.update("DELETE FROM recipe_favorites WHERE member_id = ? AND recipe_seq = ?", memberId, seq);
		if (removed > 0) return Optional.of(false);
		jdbc.update("INSERT INTO recipe_favorites (member_id, recipe_seq, recipe_name, created_at) VALUES (?, ?, ?, ?)",
				memberId, seq, recipe.get().get("name"), Timestamp.valueOf(LocalDateTime.now()));
		return Optional.of(true);
	}

	@Transactional(readOnly = true)
	public boolean isFavorite(String username, Long recipeId) {
		Integer n = jdbc.queryForObject("""
						SELECT COUNT(*) FROM recipe_favorites f JOIN users u ON u.id = f.member_id JOIN recipes r ON r.seq = f.recipe_seq
						WHERE u.username = ? AND r.id = ?""", Integer.class, username, recipeId);
		return n != null && n > 0;
	}

	/** 최근 순. 다시 적재되며 없어진 레시피는 recipeId가 null */
	@Transactional(readOnly = true)
	public List<Row> list(String username) {
		return jdbc.query("""
						SELECT f.id, r.id, f.recipe_name, r.category, f.created_at
						FROM recipe_favorites f JOIN users u ON u.id = f.member_id LEFT JOIN recipes r ON r.seq = f.recipe_seq
						WHERE u.username = ? ORDER BY f.created_at DESC, f.id DESC""",
				(rs, i) -> new Row(rs.getLong(1), (Long) rs.getObject(2), rs.getString(3), rs.getString(4),
						rs.getTimestamp(5).toLocalDateTime()), username);
	}

	public boolean delete(String username, Long favoriteId) {
		return jdbc.update("DELETE FROM recipe_favorites WHERE id = ? AND member_id = (SELECT id FROM users WHERE username = ?)",
				favoriteId, username) > 0;
	}

	/** 볼 수 있는 레시피(공공 또는 본인 개인 레시피)의 번호·이름 */
	private Optional<Map<String, Object>> recipe(String username, Long recipeId) {
		if (!myRecipeService.canView(recipeId, username)) return Optional.empty();
		List<Map<String, Object>> rows = jdbc.queryForList("SELECT seq, name FROM recipes WHERE id = ?", recipeId);
		if (rows.isEmpty()) return Optional.empty();
		Map<String, Object> r = rows.getFirst();
		// H2·MySQL 열 이름 대소문자 차이
		return Optional.of(Map.of("seq", value(r, "seq"), "name", value(r, "name")));
	}

	private static Object value(Map<String, Object> row, String column) {
		return row.containsKey(column) ? row.get(column) : row.get(column.toUpperCase());
	}
}
