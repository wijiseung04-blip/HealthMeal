package com.springboot.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 회원 기본 정보: 하루 열량 기준(KDRI)과 목표 계산에 쓴다 */
@Entity
@Table(name = "member_profiles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberProfile {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "member_id", unique = true)
	private Member member;

	private Integer birthYear;

	@Column(length = 1)
	private String sex;

	private Double heightCm;
	private Double weightKg;

	@Column(length = 20)
	private String activity;

	/** 식사 조합에서 뺄 식품 (쉼표로 구분한 낱말) */
	@Column(length = 500)
	private String excludedFoods;

	private LocalDateTime updatedAt;
}
