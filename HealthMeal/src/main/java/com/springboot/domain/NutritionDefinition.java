package com.springboot.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 영양소 코드·이름·단위. 코드는 원본의 한글 영양소명(예: 나트륨) */
@Entity
@Table(name = "nutrition_definitions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NutritionDefinition {

	@Id
	@Column(length = 100)
	private String code;

	@Column(length = 100)
	private String engName;

	@Column(length = 20)
	private String unit;

	private Integer displayOrder;
}
