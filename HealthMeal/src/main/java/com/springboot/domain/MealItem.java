package com.springboot.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 식사 조합 후보 한 가지(1회 제공량 기준 영양값).
 * 편의점·간편식: 가공식품 표준데이터 / 외식: 음식 표준데이터 / 직접 조리: 공공 레시피의 1인분 영양정보
 */
@Entity
@Table(name = "meal_items", indexes = @Index(columnList = "situation"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MealItem {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 20)
	private String situation;

	/** MAIN(주식·한 그릇) / STAPLE(밥) / SOUP(국·찌개) / SIDE(반찬·곁들임) / DRINK(음료·유제품) */
	@Column(nullable = false, length = 10)
	private String slot;

	@Column(nullable = false, length = 200)
	private String name;

	@Column(length = 100)
	private String maker;

	private Double servingGrams;
	private Double energy;
	private Double protein;
	private Double fat;
	private Double carbohydrate;
	private Double sugar;
	private Double sodium;

	/** 원본 식품코드 또는 레시피 번호(RCP_SEQ) */
	@Column(length = 30)
	private String sourceCode;
}
