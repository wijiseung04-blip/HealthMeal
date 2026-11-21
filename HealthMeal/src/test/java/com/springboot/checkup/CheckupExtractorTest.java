package com.springboot.checkup;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class CheckupExtractorTest {

	private static Map<TestItem, Double> values(String text) {
		var r = CheckupExtractor.parse(text);
		var out = new java.util.EnumMap<TestItem, Double>(TestItem.class);
		r.values().forEach((k, v) -> out.put(k, v.value()));
		return out;
	}

	@Test
	void 가상_결과표_PDF에서_13개_항목을_모두_뽑는다() throws Exception {
		var r = CheckupExtractor.extract(Files.readAllBytes(Path.of("src/test/resources/checkup-sample.pdf")));
		assertThat(r.hasText()).isTrue();
		assertThat(r.examDate()).isEqualTo(java.time.LocalDate.of(2026, 10, 15));
		var v = new java.util.EnumMap<TestItem, Double>(TestItem.class);
		r.values().forEach((k, e) -> v.put(k, e.value()));
		assertThat(v).containsExactly(
				entry(TestItem.SYSTOLIC_BP, 138.0), entry(TestItem.DIASTOLIC_BP, 88.0), entry(TestItem.FASTING_GLUCOSE, 104.0),
				entry(TestItem.TOTAL_CHOLESTEROL, 212.0), entry(TestItem.HDL, 45.0), entry(TestItem.LDL, 133.0),
				entry(TestItem.TRIGLYCERIDE, 178.0), entry(TestItem.BMI, 26.7), entry(TestItem.WAIST, 92.0),
				entry(TestItem.AST, 24.0), entry(TestItem.ALT, 31.0), entry(TestItem.CREATININE, 0.9), entry(TestItem.EGFR, 92.0));
	}

	@Test
	void 글자가_없는_PDF는_추출하지_않고_알린다() throws Exception {
		var r = CheckupExtractor.extract(Files.readAllBytes(Path.of("src/test/resources/checkup-scanned.pdf")));
		assertThat(r.hasText()).isFalse();
		assertThat(r.values()).isEmpty();
	}

	@Test
	void 참고치가_아니라_결과값을_뽑는다() {
		assertThat(values("공복혈당      104 mg/dL       100 미만")).containsEntry(TestItem.FASTING_GLUCOSE, 104.0);
		assertThat(values("혈압(최고/최저) 138/88 mmHg 120/80 미만"))
				.containsEntry(TestItem.SYSTOLIC_BP, 138.0).containsEntry(TestItem.DIASTOLIC_BP, 88.0);
	}

	@Test
	void 혈압이_두_줄로_나뉘어도_뽑는다() {
		assertThat(values("수축기 혈압 132 mmHg\n이완기 혈압 85 mmHg"))
				.containsEntry(TestItem.SYSTOLIC_BP, 132.0).containsEntry(TestItem.DIASTOLIC_BP, 85.0);
	}

	@Test
	void HDL과_LDL을_헷갈리지_않는다() {
		assertThat(values("HDL-C 52 mg/dL\nLDL-C 128 mg/dL\nTG 160"))
				.containsEntry(TestItem.HDL, 52.0).containsEntry(TestItem.LDL, 128.0).containsEntry(TestItem.TRIGLYCERIDE, 160.0);
	}

	@Test
	void 값이_없거나_말이_안_되는_숫자는_뽑지_않는다() {
		assertThat(values("LDL 콜레스테롤   미검사\n공복혈당 9999 mg/dL")).isEmpty();
	}

	@Test
	void 검진일_표기를_여러_형식으로_읽는다() {
		assertThat(CheckupExtractor.parse("검진일자: 2026.03.07").examDate()).isEqualTo(java.time.LocalDate.of(2026, 3, 7));
		assertThat(CheckupExtractor.parse("검진 날짜 2025년 11월 2일").examDate()).isEqualTo(java.time.LocalDate.of(2025, 11, 2));
		assertThat(CheckupExtractor.parse("발행일 2026-01-01").examDate()).isNull();
	}

	@Test
	void 워터마크로_값이_다음_줄로_밀려도_뽑는다() {
		// 실제 결과표 PDF: 표 위 "SAMPLE" 워터마크 글자가 끼어 행이 여러 줄로 쪼개짐
		var r = CheckupExtractor.parse("""
				중성지방 126 mg/dL 150 미만 정상
				혈액검사
				LDL-콜레스테롤 PL
				E
				103 mg/dL 130 미만(일반 참고) 정상
				만성신장질환 혈청크레아티닌
				SA
				M 0.9 mg/dL 1.5 이하 정상
				AST(SGOT) 24 U/L 40 이하 정상
				""");
		assertThat(r.values().get(TestItem.LDL).value()).isEqualTo(103);
		assertThat(r.values().get(TestItem.CREATININE).value()).isEqualTo(0.9);
		assertThat(r.values().get(TestItem.TRIGLYCERIDE).value()).isEqualTo(126);
		assertThat(r.values().get(TestItem.AST).value()).isEqualTo(24);
	}

	@Test
	void 다음_줄이_다른_항목이거나_설명_문장이면_값을_가져오지_않는다() {
		var r = CheckupExtractor.parse("""
				LDL 콜레스테롤
				AST(SGOT) 24 U/L
				혈청크레아티닌
				검사 결과 해석: 수치 1.2는 참고용입니다
				""");
		assertThat(r.values()).doesNotContainKeys(TestItem.LDL, TestItem.CREATININE);
		assertThat(r.values().get(TestItem.AST).value()).isEqualTo(24);
	}
}
