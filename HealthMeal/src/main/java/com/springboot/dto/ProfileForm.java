package com.springboot.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

/** 프로필 입력. 비워 둔 칸은 쓰지 않는다 (열량 기준은 모든 칸이 있어야 계산) */
@Getter
@Setter
public class ProfileForm {

	@Min(value = 1920, message = "{profile.birthYear.min}")
	@Max(value = 2010, message = "{profile.birthYear.max}")
	private Integer birthYear;

	@Pattern(regexp = "^(M|F)?$", message = "{profile.sex.pattern}")
	private String sex;

	@DecimalMin(value = "100", message = "{profile.heightCm.range}")
	@DecimalMax(value = "230", message = "{profile.heightCm.range}")
	private Double heightCm;

	@DecimalMin(value = "25", message = "{profile.weightKg.range}")
	@DecimalMax(value = "250", message = "{profile.weightKg.range}")
	private Double weightKg;

	@Pattern(regexp = "^(SEDENTARY|LOW_ACTIVE|ACTIVE|VERY_ACTIVE)?$", message = "{profile.activity.pattern}")
	private String activity;

	@Size(max = 300, message = "{profile.excludedFoods.size}")
	private String excludedFoods;
}
