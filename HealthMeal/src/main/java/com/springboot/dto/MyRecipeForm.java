package com.springboot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 개인 레시피 등록·수정 폼. 재료·조리 순서는 한 줄에 하나 */
@Getter
@Setter
public class MyRecipeForm {

	@NotBlank(message = "레시피 이름을 입력하세요.")
	@Size(max = 100, message = "이름은 100자 이하여야 합니다.")
	private String name;

	@Size(max = 30, message = "음식 종류는 30자 이하여야 합니다.")
	private String category;

	@NotBlank(message = "재료를 한 줄에 하나씩 입력하세요.")
	@Size(max = 3000, message = "재료는 3000자 이하여야 합니다.")
	private String ingredients;

	@Size(max = 5000, message = "조리 순서는 5000자 이하여야 합니다.")
	private String steps;
}
