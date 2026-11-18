package com.springboot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 개인 레시피 등록·수정 폼. 재료·조리 순서는 한 줄에 하나 */
@Getter
@Setter
public class MyRecipeForm {

	@NotBlank(message = "{myRecipe.name.notBlank}")
	@Size(max = 100, message = "{myRecipe.name.size}")
	private String name;

	@Size(max = 30, message = "{myRecipe.category.size}")
	private String category;

	@NotBlank(message = "{myRecipe.ingredients.notBlank}")
	@Size(max = 3000, message = "{myRecipe.ingredients.size}")
	private String ingredients;

	@Size(max = 5000, message = "{myRecipe.steps.size}")
	private String steps;
}
