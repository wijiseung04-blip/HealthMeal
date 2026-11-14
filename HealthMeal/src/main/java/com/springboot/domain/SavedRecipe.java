package com.springboot.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 회원이 저장한 맞춤 레시피(감량·대체 적용 이력).
 * 레시피는 다시 적재하면 id가 바뀌므로 원본 번호(RCP_SEQ)로 가리키고, 저장 시점의 비교 결과를 함께 남긴다.
 */
@Entity
@Table(name = "user_substitutions",
		uniqueConstraints = @UniqueConstraint(columnNames = {"member_id", "recipe_seq", "goal", "option_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SavedRecipe {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "member_id")
	private Member member;

	@Column(name = "recipe_seq", nullable = false, length = 20)
	private String recipeSeq;

	@Column(nullable = false, length = 200)
	private String recipeName;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private NutritionGoal goal;

	@Column(name = "option_id", nullable = false, length = 100)
	private String optionId;

	/** REDUCE / SUBSTITUTE */
	@Column(nullable = false, length = 20)
	private String changeType;

	@Column(nullable = false, length = 500)
	private String ingredient;

	@Column(nullable = false, length = 300)
	private String description;

	private Double gramsBefore;
	private Double gramsAfter;

	/** 저장 시점의 목표 영양소 비교 */
	private Double nutrientBefore;
	private Double nutrientAfter;
	private Double delta;
	private Double rate;

	/** EXACT / UPPER_BOUND / NOT_AVAILABLE */
	@Column(length = 20)
	private String rateKind;

	/** ACHIEVED / NOT_ENOUGH / PENDING */
	@Column(length = 20)
	private String verdict;

	@Column(nullable = false)
	private LocalDateTime savedAt;

	public SavedRecipe(Member member, String recipeSeq, String recipeName, NutritionGoal goal, String optionId, String changeType,
					   String ingredient, String description, Double gramsBefore, Double gramsAfter, Double nutrientBefore,
					   Double nutrientAfter, Double delta, Double rate, String rateKind, String verdict) {
		this.member = member;
		this.recipeSeq = recipeSeq;
		this.recipeName = recipeName;
		this.goal = goal;
		this.optionId = optionId;
		this.changeType = changeType;
		this.ingredient = ingredient;
		this.description = description;
		this.gramsBefore = gramsBefore;
		this.gramsAfter = gramsAfter;
		this.nutrientBefore = nutrientBefore;
		this.nutrientAfter = nutrientAfter;
		this.delta = delta;
		this.rate = rate;
		this.rateKind = rateKind;
		this.verdict = verdict;
		this.savedAt = LocalDateTime.now();
	}

	/** 같은 맞춤 레시피를 다시 저장하면 새로 만들지 않고 저장 시각만 갱신 */
	public void touch() {
		this.savedAt = LocalDateTime.now();
	}
}
