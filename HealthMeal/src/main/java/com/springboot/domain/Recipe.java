package com.springboot.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** 식약처 COOKRCP01 레시피(owner 없음) 또는 회원이 등록한 개인 레시피(owner 있음, 본인만 조회). info* 값은 원본 1인분 기준 참고치 */
@Entity
@Table(name = "recipes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Recipe {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true, length = 20)
	private String seq;

	@Column(nullable = false, length = 200)
	private String name;

	@Column(length = 50)
	private String category;

	@Column(length = 50)
	private String cookingMethod;

	@Column(length = 500)
	private String mainImage;

	@Column(length = 200)
	private String hashTag;

	@Column(length = 2000)
	private String sodiumTip;

	@Lob
	private String ingredientText;

	private Double infoEnergy;
	private Double infoCarbohydrate;
	private Double infoProtein;
	private Double infoFat;
	private Double infoSodium;

	/** 개인 레시피 주인. 공공 레시피는 null */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "owner_id")
	private Member owner;

	@OneToMany(mappedBy = "recipe")
	@OrderBy("stepNo")
	private List<RecipeStep> steps = new ArrayList<>();

	@OneToMany(mappedBy = "recipe")
	@OrderBy("position")
	private List<RecipeIngredient> ingredients = new ArrayList<>();
}
