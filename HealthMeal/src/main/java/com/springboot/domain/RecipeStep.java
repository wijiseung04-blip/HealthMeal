package com.springboot.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 레시피 조리 순서 한 단계: 순번, 설명 문장, 단계 사진 주소 */
@Entity
@Table(name = "recipe_steps")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecipeStep {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "recipe_id")
	private Recipe recipe;

	private int stepNo;

	@Column(length = 2000)
	private String description;

	@Column(length = 500)
	private String image;
}
