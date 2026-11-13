package com.springboot.controller;

import com.springboot.service.RecipeNutritionService;
import com.springboot.service.RecipeNutritionService.RecipeNutrition;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 레시피 영양 계산 결과 (비회원도 조회 가능) */
@RestController
@RequestMapping("/api/recipes")
@RequiredArgsConstructor
public class RecipeApiController {

	private final RecipeNutritionService nutritionService;

	@GetMapping("/{id}/nutrition")
	public ResponseEntity<RecipeNutrition> nutrition(@PathVariable Long id) {
		return nutritionService.calculate(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
	}
}
