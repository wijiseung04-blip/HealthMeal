package com.springboot.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 회원이 즐겨찾기한 레시피. 레시피는 다시 적재하면 id가 바뀌므로 원본 번호(RCP_SEQ, 개인 레시피는 U…)로 가리킨다
 */
@Entity
@Table(name = "recipe_favorites", uniqueConstraints = @UniqueConstraint(columnNames = {"member_id", "recipe_seq"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecipeFavorite {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "member_id")
	private Member member;

	@Column(name = "recipe_seq", nullable = false, length = 20)
	private String recipeSeq;

	/** 저장 시점 이름 (레시피가 없어져도 목록에 보여 줄 수 있게) */
	@Column(nullable = false, length = 200)
	private String recipeName;

	@Column(nullable = false)
	private LocalDateTime createdAt;
}
