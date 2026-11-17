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

	@Test
	void 한도_초과_응답에서_다시_시도할_시간을_읽는다() {
		String body = """
				{"error": {"code": 429, "details": [{"@type": "type.googleapis.com/google.rpc.QuotaFailure"},
				 {"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "37.5s"}]}}""";
		assertThat(GeminiCheckupVision.retryDelay(body)).contains(38L);
		assertThat(GeminiCheckupVision.retryDelay("not json")).isEmpty();
		assertThat(new VisionException(VisionException.Reason.RATE_LIMIT, 38).userMessage()).contains("38초");
	}

	@Test
	void 큰_사진은_긴_변_1600px_JPEG로_줄여_보낸다() throws Exception {
		var img = new java.awt.image.BufferedImage(4000, 3000, java.awt.image.BufferedImage.TYPE_INT_RGB);
		var g = img.createGraphics();
		for (int x = 0; x < 4000; x += 7) {
			g.setColor(new java.awt.Color((x * 37) % 255, (x * 11) % 255, (x * 3) % 255));
			g.drawLine(x, 0, 4000 - x, 3000);
		}
		g.dispose();
		var png = new java.io.ByteArrayOutputStream();
		javax.imageio.ImageIO.write(img, "png", png);
		var out = GeminiCheckupVision.shrink(png.toByteArray(), "image/png");
		assertThat(out.mimeType()).isEqualTo("image/jpeg");
		assertThat(out.data().length).isLessThan(png.size());
		var read = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(out.data()));
		assertThat(read.getWidth()).isEqualTo(1600);
		assertThat(read.getHeight()).isEqualTo(1200);
		// 읽을 수 없는 형식은 그대로
		byte[] webp = {1, 2, 3};
		assertThat(GeminiCheckupVision.shrink(webp, "image/webp").data()).isSameAs(webp);
	}

	@Test
	void 생각_단계를_낮춰_보내고_빼는_요청도_만든다() {
		assertThat(GeminiCheckupVision.body("image/jpeg", "AAAA", true)).contains("\"thinkingLevel\":\"low\"").contains("image/jpeg");
		assertThat(GeminiCheckupVision.body("image/jpeg", "AAAA", false)).doesNotContain("thinking");
		assertThat(GeminiCheckupVision.BUDGET.toSeconds()).isEqualTo(300);
	}
}
