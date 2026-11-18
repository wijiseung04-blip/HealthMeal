package com.springboot.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** 11장 @ResponseStatus: 없거나 볼 수 없는 레시피(다른 회원의 개인 레시피 포함)면 404 */
@Getter
@ResponseStatus(value = HttpStatus.NOT_FOUND, reason = "레시피를 찾을 수 없습니다.")
public class RecipeNotFoundException extends RuntimeException {

	private final Long recipeId;

	public RecipeNotFoundException(Long recipeId) {
		super("레시피를 찾을 수 없습니다: " + recipeId);
		this.recipeId = recipeId;
	}
}
