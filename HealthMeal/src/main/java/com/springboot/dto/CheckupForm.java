package com.springboot.dto;

import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** 검진 수치 확인·입력 폼. values의 키는 TestItem 이름 */
@Getter
@Setter
public class CheckupForm {

	private Long fileId;

	/** M / F (허리둘레 판정 기준이 성별로 다르다) */
	private String sex;

	@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
	private LocalDate examDate;

	private Map<String, String> values = new LinkedHashMap<>();

	public static final String AI = "AI";

	/** 자동 추출된 값 (사용자가 고쳤는지 구분하는 데 쓴다) */
	private Map<String, String> extracted = new LinkedHashMap<>();

	/** 자동 추출 방법: null이면 PDF 글자 규칙, AI면 Gemini 판독 */
	private String extractMethod;
}
