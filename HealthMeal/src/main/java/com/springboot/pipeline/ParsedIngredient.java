package com.springboot.pipeline;

/**
 * 레시피 재료 문자열 한 항목의 파싱 결과.
 * grams는 g 단위가 확정된 경우에만 채운다(ml·개·큰술 등은 환산표로 나중에 채움).
 */
public record ParsedIngredient(
		String section,  // 주재료, 양념장 등 소제목 (없으면 null)
		String raw,      // 원문 항목
		String name,     // 재료명 (괄호·수량 제거)
		String note,     // 괄호 안 부가 설명 (포항초, 3/4모 등)
		Double amount,   // 수량
		String unit,     // 정규화 단위: g, ml, 큰술, 작은술, 컵, 개 ... (없으면 null)
		Double grams,    // 확정 중량(g)
		Status status) {

	public enum Status {
		GRAM,             // g·kg 표기
		ML,               // ml·L 표기, 밀도 환산 필요
		ASSUMED_GRAM,     // 단위 없는 숫자, 같은 레시피 표기 관례상 g으로 간주
		NEEDS_CONVERSION, // 개·큰술·장 등, 재료별 환산표 필요
		VAGUE,            // 약간·적당량
		NO_AMOUNT         // 수량 없음
	}
}
