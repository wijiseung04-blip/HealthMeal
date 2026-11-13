package com.springboot.controller;

import com.springboot.domain.NutritionGoal;
import com.springboot.service.RecipeNutritionService;
import com.springboot.service.SubstitutionService;
import com.springboot.service.RecipeNutritionService.RecipeNutrition;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 레시피 영양 계산 결과 (비회원도 조회 가능) */
@RestController
@RequestMapping("/api/recipes")
@RequiredArgsConstructor
public class RecipeApiController {

	private final RecipeNutritionService nutritionService;
	private final SubstitutionService substitutionService;

	@GetMapping("/{id}/nutrition")
	// 이름을 명시: 이클립스 컴파일러는 기본적으로 -parameters 없이 빌드해 매개변수 이름을 알 수 없다
	public ResponseEntity<RecipeNutrition> nutrition(@PathVariable("id") Long id) {
		return nutritionService.calculate(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
	}

	@GetMapping("/{id}/substitutions")
	public ResponseEntity<SubstitutionService.Suggestions> substitutions(@PathVariable("id") Long id,
																		 @RequestParam(name = "goal", defaultValue = "LOW_SODIUM") NutritionGoal goal) {
		return substitutionService.suggest(id, goal).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
	}
}
