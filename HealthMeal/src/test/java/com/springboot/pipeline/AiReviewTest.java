package com.springboot.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class AiReviewTest {

	private static FoodRecord food(String code, String name) {
		return new FoodRecord(code, name, null, null, null, List.of());
	}

	private final List<FoodRecord> foods = List.of(
			food("F1", "파, 쪽파, 생것"), food("F2", "파, 대파, 생것"),
			food("D1", "다시마, 생것"), food("D2", "다시마, 말린것"),
			food("S1", "설탕, 백설탕"), food("M1", "우유"), food("M2", "우유, 저지방"));

	/** 질문 종류별로 정해진 답을 하는 가짜 AI. 받은 질문을 기록한다 */
	private final List<String> prompts = new ArrayList<>();
	private final AiReview.Ai fake = prompt -> {
		prompts.add(prompt);
		List<String> ids = new ArrayList<>();
		Matcher m = Pattern.compile("id (\\d+):").matcher(prompt);
		while (m.find()) ids.add(m.group(1));
		if (prompt.contains("식품성분표 항목")) { // 0: 실파 → F1 (일치), 1: 다시마 → D1 (제안은 D2, 불일치)
			return "[{\"id\": 0, \"code\": \"F1\", \"reason\": \"실파는 쪽파\"}, {\"id\": 1, \"code\": \"D1\", \"reason\": \"생것\"}]";
		}
		if (prompt.contains("대체가")) return "[{\"id\": " + ids.getFirst() + ", \"ok\": true, \"reason\": \"무난\"}]";
		return "```json\n[{\"id\": 0, \"grams\": 130, \"reason\": \"중간 크기\"}, {\"id\": 1, \"grams\": 10, \"reason\": \"생대추\"}]\n```";
	};

	@Test
	void 일치하면_AI로_표시하고_불일치는_사람_확인으로_남긴다(@TempDir Path dir) throws Exception {
		Path mapping = Files.createDirectories(dir.resolve("mapping"));
		Files.writeString(mapping.resolve("ingredient-mappings-manual.csv"), """
				"ingredient","food_code","confirmed","comment"\r
				"실파","F1","","Claude 제안. 쪽파"\r
				"다시마","D2","","Claude 제안. 말린것"\r
				"설탕","S1","O","사람 확인"\r
				""");
		Files.writeString(mapping.resolve("substitute-pool.csv"), """
				"source_food_code","candidate_food_code","min_ratio","max_ratio","culinary_role","caution","confirmed","comment"
				"M1","M2","1.0","1.0","유제품","고소한 맛이 약해짐","","Claude 제안"
				""");
		Files.writeString(mapping.resolve("unit-conversions-manual.csv"), """
				"ingredient","unit","grams_per_unit","confirmed","comment"
				"레몬","개","120","","Claude 제안. 중간 크기 1개(껍질 포함). 확인 후 confirmed에 O"
				"대추","알","4","","Claude 제안. 말린 대추 1알, 원문 중앙값 4g과 같음"
				""");
		AiReview review = new AiReview(fake, foods, mapping, dir.resolve("review"), dir.resolve("report"));

		var findings = review.review();

		assertThat(findings).extracting(AiReview.Finding::type, AiReview.Finding::item, AiReview.Finding::agree).containsExactly(
				org.assertj.core.groups.Tuple.tuple("식품 연결", "실파", true),
				org.assertj.core.groups.Tuple.tuple("식품 연결", "다시마", false),
				org.assertj.core.groups.Tuple.tuple("대체 규칙", "우유 → 우유, 저지방", true),
				org.assertj.core.groups.Tuple.tuple("개당 중량", "레몬 1개", true),   // 120 vs 130: 20% 안
				org.assertj.core.groups.Tuple.tuple("개당 중량", "대추 1알", false)); // 4 vs 10

		// 일치만 AI, 사람 확인(O)은 그대로, 사람이 고친 파일의 줄바꿈(CRLF) 유지
		String mappings = Files.readString(mapping.resolve("ingredient-mappings-manual.csv"));
		assertThat(mappings).contains("\"실파\",\"F1\",\"AI\"", "\"다시마\",\"D2\",\"\"", "\"설탕\",\"S1\",\"O\"").contains("\r\n");
		assertThat(Files.readString(mapping.resolve("substitute-pool.csv"))).contains("\"AI\"");
		assertThat(Files.readString(mapping.resolve("unit-conversions-manual.csv"))).contains("\"레몬\",\"개\",\"120\",\"AI\"", "\"대추\",\"알\",\"4\",\"\"");

		// AI에게 우리 제안 숫자를 보여 주지 않는다. 식품은 제안 외 후보도 함께 보여 준다
		String all = String.join("\n", prompts);
		assertThat(all).doesNotContain("120", "4g", "Claude").contains("F2 파, 대파, 생것", "D1 다시마, 생것");
		assertThat(Files.readString(dir.resolve("report").resolve("ai-review.md"))).contains("| 식품 연결 | 다시마 |", "| 개당 중량 | 대추 1알 |");

		// 다시 실행: 식품 연결을 확인 대기로 되돌려도 같은 질문이라 저장된 답을 쓴다.
		// 대체 규칙·레몬은 이미 AI라 빠지고, 남은 대추만 새 질문 1번
		int calls = prompts.size();
		Path m = mapping.resolve("ingredient-mappings-manual.csv");
		Files.writeString(m, Files.readString(m).replace("\"AI\"", "\"\""));
		review.review();
		assertThat(prompts).hasSize(calls + 1);
		assertThat(prompts.getLast()).contains("대추").doesNotContain("레몬");
	}
}
