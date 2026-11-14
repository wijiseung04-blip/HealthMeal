package com.springboot.checkup;

import java.util.List;

/**
 * 표준 검사 항목. 동의어로 결과표의 다양한 표기를 맞추고, 가능한 값 범위로 잘못 읽은 숫자를 거른다.
 * 대상: 국민건강보험 일반건강검진 공통 항목
 */
public enum TestItem {

	SYSTOLIC_BP("수축기 혈압", "mmHg", 60, 260, List.of("수축기혈압", "수축기")),
	DIASTOLIC_BP("이완기 혈압", "mmHg", 30, 160, List.of("이완기혈압", "이완기")),
	FASTING_GLUCOSE("공복혈당", "mg/dL", 40, 600, List.of("공복혈당", "공복시혈당", "식전혈당", "혈당")),
	TOTAL_CHOLESTEROL("총콜레스테롤", "mg/dL", 50, 600, List.of("총콜레스테롤", "총콜레스테롤(T-Chol)", "T-Chol", "Total Cholesterol")),
	HDL("HDL 콜레스테롤", "mg/dL", 5, 200, List.of("HDL콜레스테롤", "HDL-콜레스테롤", "고밀도콜레스테롤", "HDL-C", "HDL")),
	LDL("LDL 콜레스테롤", "mg/dL", 10, 500, List.of("LDL콜레스테롤", "LDL-콜레스테롤", "저밀도콜레스테롤", "LDL-C", "LDL")),
	TRIGLYCERIDE("중성지방", "mg/dL", 10, 3000, List.of("중성지방", "트리글리세라이드", "트리글리세리드", "TG")),
	BMI("체질량지수", "kg/m²", 10, 70, List.of("체질량지수", "BMI")),
	WAIST("허리둘레", "cm", 40, 200, List.of("허리둘레")),
	AST("AST", "U/L", 1, 2000, List.of("AST(SGOT)", "SGOT", "AST")),
	ALT("ALT", "U/L", 1, 2000, List.of("ALT(SGPT)", "SGPT", "ALT")),
	CREATININE("혈청 크레아티닌", "mg/dL", 0.1, 20, List.of("혈청크레아티닌", "크레아티닌", "Creatinine")),
	EGFR("신사구체여과율", "mL/min/1.73m²", 1, 200, List.of("신사구체여과율(e-GFR)", "신사구체여과율", "e-GFR", "eGFR"));

	private final String label;
	private final String unit;
	private final double min;
	private final double max;
	private final List<String> synonyms;

	TestItem(String label, String unit, double min, double max, List<String> synonyms) {
		this.label = label;
		this.unit = unit;
		this.min = min;
		this.max = max;
		this.synonyms = synonyms;
	}

	public String getLabel() {
		return label;
	}

	public String getUnit() {
		return unit;
	}

	public List<String> synonyms() {
		return synonyms;
	}

	/** 사람이 측정할 수 있는 범위 안인지 (잘못 읽은 숫자·입력 실수 거르기) */
	public boolean plausible(double value) {
		return value >= min && value <= max;
	}

	public String rangeText() {
		return trim(min) + "~" + trim(max);
	}

	private static String trim(double d) {
		return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
	}
}
