package com.springboot.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 레시피 재료 한 줄의 역할 분석 결과 (기획서 7-2 ingredient_analysis). 재료가 다시 적재되면 함께 다시 만든다.
 * method: RULE(규칙). 나중에 AI 분석을 붙이면 같은 표에 method·model·검수 상태로 구분해 남긴다
 */
@Entity
@Table(name = "ingredient_analysis", indexes = @Index(columnList = "recipe_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IngredientAnalysisRecord {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** recipe_ingredients.id (재적재 순서와 무관하게 지우고 다시 넣으므로 외래키는 두지 않는다) */
	@Column(name = "recipe_ingredient_id", nullable = false, unique = true)
	private Long recipeIngredientId;

	@Column(name = "recipe_id", nullable = false)
	private Long recipeId;

	@Column(nullable = false, length = 20)
	private String culinaryRole;

	@Column(nullable = false, length = 20)
	private String nutritionRole;

	@Column(length = 300)
	private String evidence;

	@Column(nullable = false, length = 10)
	private String method;

	private LocalDateTime analyzedAt;
}
