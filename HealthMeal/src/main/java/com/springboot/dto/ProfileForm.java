package com.springboot.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

/** 프로필 입력. 비워 둔 칸은 쓰지 않는다 (열량 기준은 모든 칸이 있어야 계산) */
@Getter
@Setter
public class ProfileForm {

	@Min(value = 1920, message = "출생연도를 확인하세요.")
	@Max(value = 2010, message = "만 15세 이상 성인 기준입니다.")
	private Integer birthYear;

	@Pattern(regexp = "^(M|F)?$", message = "성별을 고르세요.")
	private String sex;

	@DecimalMin(value = "100", message = "키는 100~230cm로 입력하세요.")
	@DecimalMax(value = "230", message = "키는 100~230cm로 입력하세요.")
	private Double heightCm;

	@DecimalMin(value = "25", message = "몸무게는 25~250kg으로 입력하세요.")
	@DecimalMax(value = "250", message = "몸무게는 25~250kg으로 입력하세요.")
	private Double weightKg;

	@Pattern(regexp = "^(SEDENTARY|LOW_ACTIVE|ACTIVE|VERY_ACTIVE)?$", message = "활동량을 고르세요.")
	private String activity;

	@Size(max = 300, message = "제외 식품은 300자 이하로 입력하세요.")
	private String excludedFoods;
}
