package com.springboot.service;

import com.springboot.domain.NutritionGoal;
import com.springboot.pipeline.MealItemLoader.Situation;
import com.springboot.pipeline.MealItemLoader.Slot;
import com.springboot.repository.RecipeRepository;
import com.springboot.service.MealPlanner.Combo;
import com.springboot.service.MealPlanner.Food;
import com.springboot.service.MealPlanner.Target;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 상황(편의점·외식·직접 조리)과 영양 목표에 맞는 한 끼 조합 추천 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MealPlanService {

	public static final int PAGE_SIZE = 6;

	private final JdbcTemplate jdbc;
	private final HealthCheckupService checkupService;
	private final RecipeRepository recipeRepository;

	/** 검진 기록에서 가져온 기본값: 성별과 추천 영양 목표 */
	public record Defaults(String sex, NutritionGoal goal, boolean fromCheckup) {
	}

	public record Plan(Situation situation, NutritionGoal goal, String sex, Target target, List<Combo> combos,
					   Map<String, Long> recipeIds, int candidateCount) {
	}

	public Defaults defaults(String username) {
		if (username == null) return new Defaults(null, null, false);
		return checkupService.list(username).stream().findFirst()
				.flatMap(s -> checkupService.detail(username, s.id()))
				.map(d -> new Defaults(d.sex(), d.evaluation().recommendedGoals().stream().findFirst().orElse(null), true))
				.orElse(new Defaults(null, null, false));
	}

	public Plan plan(Situation situation, NutritionGoal goal, String sex, int page) {
		List<Food> foods = jdbc.query("""
						SELECT id, slot, name, maker, serving_grams, energy, protein, fat, carbohydrate, sugar, sodium, source_code
						FROM meal_items WHERE situation = ?""",
				(rs, i) -> new Food(rs.getLong(1), Slot.valueOf(rs.getString(2)), rs.getString(3), rs.getString(4), (Double) rs.getObject(5),
						rs.getDouble(6), (Double) rs.getObject(7), (Double) rs.getObject(8), (Double) rs.getObject(9), (Double) rs.getObject(10),
						rs.getDouble(11), rs.getString(12)),
				situation.name());
		Target target = MealPlanner.target(goal, sex);
		List<Combo> combos = MealPlanner.plan(situation, foods, target, goal, Math.max(page, 0), PAGE_SIZE);

		// 직접 조리는 레시피 상세(감량·대체)로 이어 준다: 레시피 번호 → 지금 id
		Map<String, Long> recipeIds = new HashMap<>();
		if (situation == Situation.HOME) {
			Set<String> seqs = new HashSet<>();
			combos.forEach(c -> c.items().forEach(f -> {
				if (f.slot() != Slot.STAPLE && f.sourceCode() != null) seqs.add(f.sourceCode());
			}));
			if (!seqs.isEmpty()) recipeRepository.findIdsBySeqs(seqs).forEach(r -> recipeIds.put((String) r[0], ((Number) r[1]).longValue()));
		}
		return new Plan(situation, goal, sex, target, combos, recipeIds, foods.size());
	}
}
