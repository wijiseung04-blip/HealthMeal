package com.springboot.controller;

import com.springboot.domain.NutritionGoal;
import com.springboot.service.RecipeService;
import com.springboot.service.SubstitutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/** 레시피 검색·상세 화면 (비회원도 조회 가능). 매개변수 이름은 이클립스 빌드를 위해 모두 명시한다 */
@Controller
@RequestMapping("/recipes")
@RequiredArgsConstructor
public class RecipeController {

	private final RecipeService recipeService;
	private final SubstitutionService substitutionService;

	@GetMapping
	public String list(@RequestParam(name = "q", required = false) String q,
					   @RequestParam(name = "category", required = false) String category,
					   @RequestParam(name = "page", defaultValue = "0") int page,
					   Model model) {
		model.addAttribute("recipes", recipeService.search(q, category, page));
		model.addAttribute("categories", recipeService.categories());
		model.addAttribute("q", q);
		model.addAttribute("category", category);
		return "recipes/list";
	}

	/** 영양 목표에 맞춘 감량·대체 후보와 선택한 후보의 전후 비교 */
	@GetMapping("/{id}/adjust")
	public String adjust(@PathVariable("id") Long id,
						 @RequestParam(name = "goal", defaultValue = "LOW_SODIUM") NutritionGoal goal,
						 @RequestParam(name = "option", required = false) String option,
						 Model model) {
		model.addAttribute("suggestions", substitutionService.suggest(id, goal)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "레시피를 찾을 수 없습니다.")));
		model.addAttribute("goals", NutritionGoal.values());
		model.addAttribute("recipeId", id);
		if (option != null) {
			model.addAttribute("applied", substitutionService.apply(id, goal, option)
					.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "선택한 후보를 찾을 수 없습니다.")));
		}
		return "recipes/adjust";
	}

	@GetMapping("/{id}")
	public String detail(@PathVariable("id") Long id, Model model) {
		model.addAttribute("goals", NutritionGoal.values());
		model.addAttribute("recipe", recipeService.detail(id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "레시피를 찾을 수 없습니다.")));
		return "recipes/detail";
	}
}
