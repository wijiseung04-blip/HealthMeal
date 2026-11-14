package com.springboot.service;

import com.springboot.domain.Recipe;
import com.springboot.nutrition.NutritionEngine;
import com.springboot.nutrition.NutritionEngine.CoverageLevel;
import com.springboot.nutrition.NutritionEngine.ItemResult;
import com.springboot.repository.RecipeRepository;
import com.springboot.service.RecipeNutritionService.IngredientLine;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 레시피 목록·상세 화면용 조회 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecipeService {

	public static final int PAGE_SIZE = 12;
	private static final Map<String, String> UNITS = Map.of(
			"에너지", "kcal", "나트륨", "mg", "콜레스테롤", "mg");

	private final RecipeRepository recipeRepository;
	private final RecipeNutritionService nutritionService;

	public record RecipeCard(Long id, String name, String category, String cookingMethod, String mainImage) {
	}

	public record Step(int no, String description, String image) {
	}

	public record NutrientRow(String name, Double value, String unit, CoverageLevel coverage) {
	}

	public record IngredientRow(String rawText, Double grams, String gramsSource, String foodName, String matchGrade,
								Double energy, Double sodium, boolean computed, boolean fromSupplement) {
	}

	public record RecipeDetail(Long id, String name, String category, String cookingMethod, String mainImage, String hashTag,
							   String sodiumTip, List<Step> steps, List<NutrientRow> nutrients, List<IngredientRow> ingredients,
							   int computedItems, int totalItems, List<String> unknownItems) {

		/** 미확인 재료가 있으면 합계는 실제보다 작거나 같다 */
		public boolean partial() {
			return computedItems < totalItems;
		}
	}

	public Page<RecipeCard> search(String q, String category, int page) {
		Page<Recipe> result = recipeRepository.search(blankToNull(q), blankToNull(category),
				PageRequest.of(Math.max(page, 0), PAGE_SIZE, Sort.by("id")));
		return result.map(r -> new RecipeCard(r.getId(), r.getName(), r.getCategory(), r.getCookingMethod(), r.getMainImage()));
	}

	public List<String> categories() {
		return recipeRepository.findCategories();
	}

	public Optional<RecipeDetail> detail(Long id) {
		Optional<Recipe> found = recipeRepository.findById(id);
		if (found.isEmpty()) return Optional.empty();
		Recipe r = found.get();
		var nutrition = nutritionService.calculate(id).orElseThrow();
		var result = nutrition.result();

		// 원문 설명이 "1. ..."로 시작하므로 번호 목록과 겹치지 않게 앞 번호를 뗀다
		List<Step> steps = r.getSteps().stream()
				.map(s -> new Step(s.getStepNo(), s.getDescription().replaceFirst("^\\s*\\d+\\s*[.)]\\s*", ""), s.getImage())).toList();
		List<NutrientRow> nutrients = NutritionEngine.KEY_NUTRIENTS.stream()
				.map(n -> new NutrientRow(n, result.totals().get(n), UNITS.getOrDefault(n, "g"), result.coverage(n))).toList();

		List<IngredientRow> rows = new ArrayList<>();
		for (int i = 0; i < nutrition.ingredients().size(); i++) {
			IngredientLine l = nutrition.ingredients().get(i);
			ItemResult item = result.items().get(i); // 같은 순서로 계산된다
			boolean computed = !item.nutrients().isEmpty();
			rows.add(new IngredientRow(l.rawText(), l.grams(), l.gramsSource(), l.foodName(), l.matchGrade(),
					item.nutrients().get("에너지"), item.nutrients().get("나트륨"), computed,
					l.foodCode() != null && l.foodCode().contains("-"))); // 표준데이터(수산물) 식품코드에만 하이픈이 있다
		}
		return Optional.of(new RecipeDetail(r.getId(), r.getName(), r.getCategory(), r.getCookingMethod(), r.getMainImage(),
				r.getHashTag(), r.getSodiumTip(), steps, nutrients, rows,
				result.coverage().computedItems(), result.coverage().totalItems(), result.unknownItems()));
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.trim();
	}
}
