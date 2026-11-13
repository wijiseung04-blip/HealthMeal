package com.springboot.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 식재료 대체 일반 규칙: 원재료 식품 → 대체 식품, 허용 비율, 조리 역할. confirmed가 아니면 추천하지 않는다(검수 대기) */
@Entity
@Table(name = "substitute_pool", uniqueConstraints = @UniqueConstraint(columnNames = {"source_food_code", "candidate_food_code"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubstitutePool {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 20)
	private String sourceFoodCode;

	@Column(nullable = false, length = 20)
	private String candidateFoodCode;

	private double minRatio;
	private double maxRatio;

	@Column(length = 50)
	private String culinaryRole;

	@Column(length = 200)
	private String caution;

	private boolean confirmed;

	@Column(length = 300)
	private String comment;
}
