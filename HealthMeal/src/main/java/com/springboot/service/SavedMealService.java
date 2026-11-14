package com.springboot.service;

import com.springboot.domain.NutritionGoal;
import com.springboot.pipeline.MealItemLoader.Situation;
import com.springboot.pipeline.MealItemLoader.Slot;
import com.springboot.repository.MemberRepository;
import com.springboot.repository.RecipeRepository;
import com.springboot.service.MealPlanner.Food;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

/** 마음에 드는 식사 조합을 마이페이지에 저장 (본인만) */
@Service
@RequiredArgsConstructor
@Transactional
public class SavedMealService {

	private final JdbcTemplate jdbc;
	private final MemberRepository memberRepository;
	private final RecipeRepository recipeRepository;

	public record Item(String slot, String name, double energy, double sodium, Double protein, Long recipeId) {
	}

	public record Row(Long id, Situation situation, NutritionGoal goal, List<Item> items, double energy, double sodium, double protein,
					  LocalDateTime savedAt) {
	}

	/** 화면에서 받은 품목 id로 서버에서 다시 읽어 저장한다 (합계도 다시 계산). 상황이 다른 품목이 섞이면 저장하지 않는다 */
	public Optional<Long> save(String username, Situation situation, NutritionGoal goal, List<Long> itemIds) {
		if (itemIds.isEmpty() || itemIds.size() > 4) return Optional.empty();
		List<Food> foods = new ArrayList<>();
		for (Long id : itemIds) {
			List<Food> f = jdbc.query("""
							SELECT id, slot, name, maker, serving_grams, energy, protein, fat, carbohydrate, sugar, sodium, source_code
							FROM meal_items WHERE id = ? AND situation = ?""",
					(rs, i) -> new Food(rs.getLong(1), Slot.valueOf(rs.getString(2)), rs.getString(3), rs.getString(4), (Double) rs.getObject(5),
							rs.getDouble(6), (Double) rs.getObject(7), (Double) rs.getObject(8), (Double) rs.getObject(9), (Double) rs.getObject(10),
							rs.getDouble(11), rs.getString(12)),
					id, situation.name());
			if (f.isEmpty()) return Optional.empty();
			foods.add(f.getFirst());
		}
		var totals = MealPlanner.totals(foods);
		StringBuilder items = new StringBuilder();
		for (Food f : foods) {
			items.append(String.join("|", f.slot().name(), f.name().replace("|", "/").replace("\n", " "), String.valueOf(f.energy()),
					String.valueOf(f.sodium()), f.protein() == null ? "" : String.valueOf(f.protein()),
					situation == Situation.HOME && f.slot() != Slot.STAPLE && f.sourceCode() != null ? f.sourceCode() : "")).append('\n');
		}
		String key = cut(situation.name() + ":" + String.join("+", foods.stream().map(Food::name).toList()), 300);
		Long memberId = memberRepository.findByUsername(username).orElseThrow().getId();
		List<Long> existing = jdbc.queryForList("SELECT id FROM saved_meals WHERE member_id = ? AND item_key = ?", Long.class, memberId, key);
		if (!existing.isEmpty()) {
			jdbc.update("UPDATE saved_meals SET saved_at = ?, goal = ? WHERE id = ?", Timestamp.valueOf(LocalDateTime.now()),
					goal == null ? null : goal.name(), existing.getFirst());
			return Optional.of(existing.getFirst());
		}
		jdbc.update("INSERT INTO saved_meals (member_id, situation, goal, item_key, items, energy, sodium, protein, saved_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
				memberId, situation.name(), goal == null ? null : goal.name(), key, cut(items.toString(), 2000),
				totals.energy(), totals.sodium(), totals.protein(), Timestamp.valueOf(LocalDateTime.now()));
		return Optional.of(jdbc.queryForObject("SELECT id FROM saved_meals WHERE member_id = ? AND item_key = ?", Long.class, memberId, key));
	}

	@Transactional(readOnly = true)
	public List<Row> list(String username) {
		List<Row> rows = jdbc.query("""
						SELECT s.id, s.situation, s.goal, s.items, s.energy, s.sodium, s.protein, s.saved_at
						FROM saved_meals s JOIN users u ON u.id = s.member_id WHERE u.username = ? ORDER BY s.saved_at DESC""",
				(rs, i) -> new Row(rs.getLong(1), Situation.valueOf(rs.getString(2)), rs.getString(3) == null ? null : NutritionGoal.valueOf(rs.getString(3)),
						parse(rs.getString(4)), rs.getDouble(5), rs.getDouble(6), rs.getDouble(7), rs.getTimestamp(8).toLocalDateTime()),
				username);
		// 직접 조리 품목은 지금의 레시피 id로 연결 (다시 적재해도 레시피 번호로 찾는다)
		Set<String> seqs = new HashSet<>();
		rows.forEach(r -> r.items().forEach(it -> {
			if (it.recipeId() != null) seqs.add(String.valueOf(it.recipeId()));
		}));
		if (seqs.isEmpty()) return rows;
		Map<String, Long> ids = new HashMap<>();
		recipeRepository.findIdsBySeqs(seqs).forEach(o -> ids.put((String) o[0], ((Number) o[1]).longValue()));
		return rows.stream().map(r -> new Row(r.id(), r.situation(), r.goal(), r.items().stream()
						.map(it -> new Item(it.slot(), it.name(), it.energy(), it.sodium(), it.protein(),
								it.recipeId() == null ? null : ids.get(String.valueOf(it.recipeId()))))
						.toList(), r.energy(), r.sodium(), r.protein(), r.savedAt()))
				.toList();
	}

	public boolean delete(String username, Long id) {
		return jdbc.update("DELETE FROM saved_meals WHERE id = ? AND member_id = (SELECT id FROM users WHERE username = ?)", id, username) > 0;
	}

	/** recipeId 칸에는 우선 레시피 번호(숫자)를 담아 두고, list()에서 지금 id로 바꾼다 */
	private static List<Item> parse(String text) {
		List<Item> out = new ArrayList<>();
		for (String line : text.split("\n")) {
			String[] p = line.split("\\|", -1);
			if (p.length < 6) continue;
			out.add(new Item(p[0], p[1], Double.parseDouble(p[2]), Double.parseDouble(p[3]), p[4].isEmpty() ? null : Double.valueOf(p[4]),
					p[5].isEmpty() ? null : Long.valueOf(p[5])));
		}
		return out;
	}

	private static String cut(String v, int max) {
		return v.length() <= max ? v : v.substring(0, max);
	}
}
