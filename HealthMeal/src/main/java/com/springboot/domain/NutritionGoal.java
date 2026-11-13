package com.springboot.domain;

/** 영양 목표 프리셋. 목표 영양소와 개선 방향 */
public enum NutritionGoal {

	LOW_SODIUM("저나트륨", "나트륨", "mg", false),
	LOW_CALORIE("저칼로리", "에너지", "kcal", false),
	HIGH_PROTEIN("고단백", "단백질", "g", true);

	/** 목표 달성 판정 기준: 목표 방향으로 10% 이상 변화 (초기 기준, 테스트 후 조정) */
	public static final double THRESHOLD = 0.10;

	private final String label;
	private final String nutrient;
	private final String unit;
	private final boolean increase;

	NutritionGoal(String label, String nutrient, String unit, boolean increase) {
		this.label = label;
		this.nutrient = nutrient;
		this.unit = unit;
		this.increase = increase;
	}

	public String getLabel() {
		return label;
	}

	public String getNutrient() {
		return nutrient;
	}

	public String getUnit() {
		return unit;
	}

	public boolean isIncrease() {
		return increase;
	}

	/** 변화량이 목표 방향인지 */
	public boolean improves(double delta) {
		return increase ? delta > 0 : delta < 0;
	}
}
