package com.springboot.checkup;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiCheckupVisionTest {

	@Test
	void AI_답에서_범위_안의_값만_받는다() throws Exception {
		var r = GeminiCheckupVision.parse("""
				{"examDate": "2026-10-15",
				 "values": {"FASTING_GLUCOSE": 104, "LDL": "132", "TRIGLYCERIDE": 99999, "WAIST": "모름", "UNKNOWN": 1,
				            "SYSTOLIC_BP": 128, "DIASTOLIC_BP": 82},
				 "lines": {"FASTING_GLUCOSE": "공복혈당 104 mg/dL"}}""");
		assertThat(r.examDate()).isEqualTo(LocalDate.of(2026, 10, 15));
		assertThat(r.values()).containsOnlyKeys(TestItem.FASTING_GLUCOSE, TestItem.LDL, TestItem.SYSTOLIC_BP, TestItem.DIASTOLIC_BP);
		assertThat(r.values().get(TestItem.LDL).value()).isEqualTo(132);
		assertThat(r.values().get(TestItem.FASTING_GLUCOSE).sourceLine()).contains("104");
	}

	@Test
	void 혈압은_둘_다_있고_수축기가_클_때만_받는다() throws Exception {
		assertThat(GeminiCheckupVision.parse("{\"values\": {\"SYSTOLIC_BP\": 128}}").values()).isEmpty();
		assertThat(GeminiCheckupVision.parse("{\"values\": {\"SYSTOLIC_BP\": 80, \"DIASTOLIC_BP\": 120}}").values()).isEmpty();
	}

	@Test
	void 코드블록으로_감싼_답과_미래_날짜를_처리한다() throws Exception {
		var r = GeminiCheckupVision.parse("```json\n{\"examDate\": \"2999-01-01\", \"values\": {\"BMI\": 23.4}}\n```");
		assertThat(r.examDate()).isNull();
		assertThat(r.values().get(TestItem.BMI).value()).isEqualTo(23.4);
	}

	@Test
	void 프롬프트에_모든_항목과_추측_금지가_있다() {
		assertThat(GeminiCheckupVision.prompt()).contains("FASTING_GLUCOSE", "EGFR", "추측하지 마세요", "참고치");
		assertThat(new GeminiCheckupVision("", "m").available()).isFalse();
	}
}
