package com.springboot.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 회원의 영양 목표 한 항목(하루 기준). priority 1이 주목표, source: CHECKUP(검진으로 생성) / USER(직접 입력·수정) */
@Entity
@Table(name = "nutrition_goal_items", uniqueConstraints = @UniqueConstraint(columnNames = {"member_id", "nutrient"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NutritionGoalItem {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "member_id")
	private Member member;

	@Column(nullable = false, length = 30)
	private String nutrient;

	/** MAX / MIN / RANGE */
	@Column(nullable = false, length = 10)
	private String goalType;

	private Double minValue;
	private Double maxValue;

	@Column(nullable = false)
	private int priority;

	@Column(nullable = false, length = 10)
	private String source;

	@Column(length = 300)
	private String reason;

	private LocalDateTime updatedAt;
}
