package com.springboot.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 국가표준식품. 영양성분은 가식부 100g 기준으로 food_nutrients에 세로형 저장 */
@Entity
@Table(name = "foods")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Food {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true, length = 20)
	private String code;

	@Column(nullable = false, length = 200)
	private String name;

	@Column(length = 50)
	private String baseName;

	@Column(length = 50)
	private String groupName;

	@Column(length = 50)
	private String origin;

	@Column(length = 10)
	private String examYear;

	@Column(length = 50)
	private String sourceVersion;
}
