package com.springboot.pipeline;

import java.util.List;

/** 국가표준식품성분 상세 응답 한 건. 영양소 값은 가식부 100g 기준. */
public record FoodRecord(String code, String name, String group, String origin, String examYear, List<Nutrient> nutrients) {

	/** 쉼표로 나눈 첫 부분. "양파, 적색, 생것" → "양파" */
	public String baseName() {
		return name.split(",")[0].trim();
	}

	public record Nutrient(String name, String engName, String unit, Double value, ValueType type) {
	}

	public enum ValueType {
		MEASURED,     // 분석값
		ESTIMATED,    // 괄호 표기 "(1,100)" — 추정·인용값
		TRACE,        // "Tr" — 미량, 0으로 계산
		NOT_MEASURED  // "-" — 미측정, NULL
	}
}
