package com.springboot.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 레시피 재료 한 줄: 원문, 파싱 결과, 확정 중량, 매칭된 식품 */
@Entity
@Table(name = "recipe_ingredients")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecipeIngredient {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "recipe_id")
	private Recipe recipe;

	private int position;

	@Column(length = 100)
	private String section;

	@Column(nullable = false, length = 500)
	private String rawText;

	@Column(length = 200)
	private String name;

	@Column(length = 300)
	private String note;

	private Double amount;

	@Column(length = 20)
	private String unit;

	/** 계산에 쓰는 중량(g). null이면 사용량 미확인 */
	private Double grams;

	/** RAW(원문 g) / ASSUMED(단위 없는 숫자) / CONVERTED(환산) / NONE */
	@Column(nullable = false, length = 20)
	private String gramsSource;

	/** 파서 상태: GRAM, ML, ASSUMED_GRAM, NEEDS_CONVERSION, VAGUE, NO_AMOUNT, NON_FOOD */
	@Column(nullable = false, length = 20)
	private String parseStatus;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "food_id")
	private Food food;

	/** EXACT / MANUAL_CONFIRMED / SIMILAR / UNMATCHED (조리 도구는 null) */
	@Column(length = 20)
	private String matchGrade;

	@Column(length = 50)
	private String matchRule;
}
