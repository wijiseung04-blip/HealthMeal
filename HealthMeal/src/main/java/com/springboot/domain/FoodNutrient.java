package com.springboot.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 식품 100g당 영양소 값. content가 null이면 미측정(0과 다름) */
@Entity
@Table(name = "food_nutrients", uniqueConstraints = @UniqueConstraint(columnNames = {"food_id", "nutrient_code"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FoodNutrient {

	public enum ValueType { MEASURED, ESTIMATED, TRACE, NOT_MEASURED }

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "food_id")
	private Food food;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "nutrient_code")
	private NutritionDefinition nutrient;

	private Double content;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ValueType valueType;
}
