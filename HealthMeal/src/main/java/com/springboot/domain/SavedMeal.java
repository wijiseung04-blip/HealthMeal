package com.springboot.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 회원이 저장한 식사 조합. 식사 후보(meal_items)는 다시 적재하면 id가 바뀌므로 저장 시점의 품목·영양값을 그대로 남긴다.
 * items: "칸|이름|열량|나트륨|단백질|레시피번호" 줄들
 */
@Entity
@Table(name = "saved_meals", uniqueConstraints = @UniqueConstraint(columnNames = {"member_id", "item_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SavedMeal {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "member_id")
	private Member member;

	@Column(nullable = false, length = 20)
	private String situation;

	@Column(length = 20)
	private String goal;

	/** 같은 조합 중복 저장 방지: 상황 + 품목 이름 */
	@Column(name = "item_key", nullable = false, length = 300)
	private String itemKey;

	@Column(nullable = false, length = 2000)
	private String items;

	private double energy;
	private double sodium;
	private double protein;

	@Column(nullable = false)
	private LocalDateTime savedAt;
}
