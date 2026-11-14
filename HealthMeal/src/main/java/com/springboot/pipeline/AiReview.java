package com.springboot.pipeline;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Claude가 만든 확인 대기 제안을 다른 AI(Gemini)로 교차검토한다.
 * - AI에게는 우리 제안을 보여 주지 않고 독립적으로 답하게 한다 (식품은 후보 목록에서 고르기, 중량은 직접 추정)
 * - 답이 일치하면 confirmed를 "AI"로 표시해 사용하고, 다르면 사람이 확인할 목록에 남긴다
 * - 사람이 확인한 "O"는 건드리지 않는다
 * 결과: data/review/ai-review.csv, data/report/ai-review.md, 응답 캐시 data/review/raw/
 */
final class AiReview {

	/** 테스트에서 가짜 응답으로 바꿀 수 있게 */
	interface Ai {
		String ask(String prompt) throws IOException, InterruptedException;
	}

	static final String AI_CONFIRMED = "AI";
	/** 개당 중량은 두 추정이 이 비율 안이면 일치로 본다 */
	static final double WEIGHT_TOLERANCE = 0.2;
	private static final int BATCH = 15;

	record Finding(String type, String item, String proposal, String aiAnswer, boolean agree, String reason) {
	}

	private final Ai ai;
	private final Path mappingDir;
	private final Path reviewDir;
	private final Path reportDir;
	private final Map<String, FoodRecord> foodsByCode = new HashMap<>();
	private final List<FoodRecord> foods;
	private final JsonMapper json = JsonMapper.builder().build();

	AiReview(Ai ai, List<FoodRecord> foods, Path mappingDir, Path reviewDir, Path reportDir) {
		this.ai = ai;
		this.foods = foods;
		this.mappingDir = mappingDir;
		this.reviewDir = reviewDir;
		this.reportDir = reportDir;
		foods.forEach(f -> foodsByCode.put(f.code(), f));
	}

	static void run() throws Exception {
		GeminiClient gemini = GeminiClient.fromEnv();
		System.out.println("AI 교차검토: Gemini " + gemini.model());
		new AiReview(gemini, FoodSources.loadAll(), IngredientMatching.MAPPING, Path.of("data", "review"), Path.of("data", "report")).review();
	}

	List<Finding> review() throws IOException, InterruptedException {
		List<Finding> all = new ArrayList<>();
		all.addAll(reviewMappings());
		all.addAll(reviewPool());
		all.addAll(reviewWeights());

		List<List<Object>> rows = new ArrayList<>();
		for (Finding f : all) rows.add(List.of(f.type(), f.item(), f.proposal(), f.aiAnswer(), f.agree() ? "일치" : "불일치", f.reason()));
		Csv.write(reviewDir.resolve("ai-review.csv"), List.of("type", "item", "claude_proposal", "ai_answer", "result", "ai_reason"), rows);
		writeReport(all);
		return all;
	}

	// ---- 1. 식품 연결 ----

	private List<Finding> reviewMappings() throws IOException, InterruptedException {
		Path file = mappingDir.resolve("ingredient-mappings-manual.csv");
		List<List<String>> rows = Csv.read(file);
		List<Integer> targets = new ArrayList<>();
		for (int i = 0; i < rows.size(); i++) if (pending(rows.get(i), 2) && foodsByCode.containsKey(rows.get(i).get(1))) targets.add(i);

		List<Finding> out = new ArrayList<>();
		for (List<Integer> batch : batches(targets)) {
			StringBuilder p = new StringBuilder("""
					한국 가정 요리 레시피의 재료명마다, 영양 계산에 쓸 식품성분표 항목을 후보 중에서 하나 고르세요.
					레시피에서 그 재료를 보통 어떤 상태로 쓰는지(생것·말린것·가공품) 생각해서 고르고, 맞는 후보가 없으면 "NONE"으로 답하세요.
					JSON 배열로만 답하세요: [{"id": 번호, "code": "식품코드 또는 NONE", "reason": "한 줄 이유"}]

					""");
			for (int i : batch) {
				p.append("id ").append(i).append(": 재료 \"").append(rows.get(i).get(0)).append("\"\n");
				for (FoodRecord f : candidates(rows.get(i).get(0), foodsByCode.get(rows.get(i).get(1)))) {
					p.append("  - ").append(f.code()).append(" ").append(f.name()).append('\n');
				}
			}
			Map<Integer, JsonNode> answers = ask(p.toString());
			for (int i : batch) {
				List<String> r = rows.get(i);
				JsonNode a = answers.get(i);
				String code = a == null ? "" : a.path("code").asString("");
				boolean agree = code.equals(r.get(1));
				out.add(new Finding("식품 연결", r.get(0), r.get(1) + " " + foodsByCode.get(r.get(1)).name(),
						code.isEmpty() ? "(답 없음)" : code + (foodsByCode.containsKey(code) ? " " + foodsByCode.get(code).name() : ""),
						agree, a == null ? "" : a.path("reason").asString("")));
				if (agree) mark(r, 2);
			}
		}
		rewrite(file, List.of("ingredient", "food_code", "confirmed", "comment"), rows);
		return out;
	}

	/** 우리 제안을 드러내지 않도록 제안 식품과 같은 대표명·재료명을 포함하는 식품을 코드순으로 섞어 보여 준다 */
	private List<FoodRecord> candidates(String ingredient, FoodRecord proposed) {
		String word = FoodMatcher.compact(ingredient);
		Set<FoodRecord> set = new TreeSet<>(Comparator.comparing(FoodRecord::code));
		set.add(proposed);
		for (FoodRecord f : foods) {
			if (set.size() >= 12) break;
			if (f.baseName().equals(proposed.baseName()) || FoodMatcher.compact(f.name()).contains(word)) set.add(f);
		}
		return new ArrayList<>(set);
	}

	// ---- 2. 대체 규칙 ----

	private List<Finding> reviewPool() throws IOException, InterruptedException {
		Path file = mappingDir.resolve("substitute-pool.csv");
		List<List<String>> rows = Csv.read(file);
		List<Integer> targets = new ArrayList<>();
		for (int i = 0; i < rows.size(); i++) {
			List<String> r = rows.get(i);
			if (pending(r, 6) && foodsByCode.containsKey(r.get(0)) && foodsByCode.containsKey(r.get(1))) targets.add(i);
		}
		List<Finding> out = new ArrayList<>();
		for (List<Integer> batch : batches(targets)) {
			StringBuilder p = new StringBuilder("""
					건강한 식단을 위해 한국 가정 요리 레시피의 재료를 다른 재료로 바꾸는 규칙들입니다.
					일반적인 가정 요리에서 이 대체가 맛·조리 면에서 무리 없이 쓸 만한지 판단하세요. 주의할 점이 있어도 쓸 만하면 true입니다.
					JSON 배열로만 답하세요: [{"id": 번호, "ok": true 또는 false, "reason": "한 줄 이유"}]

					""");
			for (int i : batch) {
				List<String> r = rows.get(i);
				p.append("id ").append(i).append(": ").append(foodsByCode.get(r.get(0)).name()).append(" → ").append(foodsByCode.get(r.get(1)).name())
						.append(" (중량 비율 ").append(r.get(2)).append("~").append(r.get(3)).append("배, 용도: ").append(r.get(4)).append(")\n");
			}
			Map<Integer, JsonNode> answers = ask(p.toString());
			for (int i : batch) {
				List<String> r = rows.get(i);
				JsonNode a = answers.get(i);
				boolean agree = a != null && a.path("ok").asBoolean(false);
				out.add(new Finding("대체 규칙", foodsByCode.get(r.get(0)).name() + " → " + foodsByCode.get(r.get(1)).name(), "쓸 만함",
						a == null ? "(답 없음)" : agree ? "쓸 만함" : "부적절", agree, a == null ? "" : a.path("reason").asString("")));
				if (agree) mark(r, 6);
			}
		}
		rewrite(file, List.of("source_food_code", "candidate_food_code", "min_ratio", "max_ratio", "culinary_role", "caution", "confirmed", "comment"), rows);
		return out;
	}

	// ---- 3. 개당 중량 ----

	private List<Finding> reviewWeights() throws IOException, InterruptedException {
		Path file = mappingDir.resolve("unit-conversions-manual.csv");
		List<List<String>> rows = Csv.read(file);
		List<Integer> targets = new ArrayList<>();
		for (int i = 0; i < rows.size(); i++) if (pending(rows.get(i), 3)) targets.add(i);

		List<Finding> out = new ArrayList<>();
		for (List<Integer> batch : batches(targets)) {
			StringBuilder p = new StringBuilder("""
					한국 가정 요리 레시피에 나오는 재료 1단위의 무게(g)를 추정하세요. 시중에서 흔한 중간 크기 기준입니다.
					설명에 가식부(먹는 부분)라고 되어 있으면 껍질·씨를 뺀 무게로 답하세요.
					JSON 배열로만 답하세요: [{"id": 번호, "grams": 숫자, "reason": "한 줄 근거"}]

					""");
			for (int i : batch) {
				List<String> r = rows.get(i);
				p.append("id ").append(i).append(": ").append(r.get(0)).append(" 1").append(r.get(1))
						.append(" — ").append(basis(r.size() > 4 ? r.get(4) : "")).append('\n');
			}
			Map<Integer, JsonNode> answers = ask(p.toString());
			for (int i : batch) {
				List<String> r = rows.get(i);
				double ours = Double.parseDouble(r.get(2));
				JsonNode a = answers.get(i);
				double theirs = a == null ? Double.NaN : a.path("grams").asDouble(Double.NaN);
				boolean agree = !Double.isNaN(theirs) && Math.abs(theirs - ours) <= ours * WEIGHT_TOLERANCE;
				out.add(new Finding("개당 중량", r.get(0) + " 1" + r.get(1), Csv.num(ours) + "g",
						Double.isNaN(theirs) ? "(답 없음)" : Csv.num(theirs) + "g", agree, a == null ? "" : a.path("reason").asString("")));
				if (agree) mark(r, 3);
			}
		}
		rewrite(file, List.of("ingredient", "unit", "grams_per_unit", "confirmed", "comment"), rows);
		return out;
	}

	/** 설명에서 우리 추정값(숫자+g)과 "Claude 제안" 문구를 지워 AI가 따라 하지 않게 한다 */
	static String basis(String comment) {
		return comment.replace("Claude 제안.", "").replaceAll("\\d+(\\.\\d+)?\\s*g", "").replaceAll("확인 후 confirmed에 O", "").trim();
	}

	// ---- 공통 ----

	private static boolean pending(List<String> r, int confirmedCol) {
		return r.size() > confirmedCol && r.get(confirmedCol).isBlank();
	}

	private static void mark(List<String> r, int confirmedCol) {
		r.set(confirmedCol, AI_CONFIRMED);
	}

	private static List<List<Integer>> batches(List<Integer> ids) {
		List<List<Integer>> out = new ArrayList<>();
		for (int i = 0; i < ids.size(); i += BATCH) out.add(ids.subList(i, Math.min(ids.size(), i + BATCH)));
		return out;
	}

	/** 같은 질문은 저장된 답을 쓴다 (다시 실행해도 API를 또 부르지 않음) */
	private Map<Integer, JsonNode> ask(String prompt) throws IOException, InterruptedException {
		Path cache = reviewDir.resolve("raw").resolve(sha(prompt) + ".json");
		String answer;
		if (Files.exists(cache)) {
			answer = Files.readString(cache, StandardCharsets.UTF_8);
		} else {
			answer = ai.ask(prompt);
			Files.createDirectories(cache.getParent());
			Files.writeString(cache, answer, StandardCharsets.UTF_8);
		}
		Map<Integer, JsonNode> byId = new HashMap<>();
		JsonNode root = json.readTree(answer.replaceAll("(?s)^```(json)?|```$", "").trim());
		for (JsonNode n : root) byId.put(n.path("id").asInt(-1), n);
		return byId;
	}

	private static void rewrite(Path file, List<String> header, List<List<String>> rows) throws IOException {
		List<List<Object>> out = new ArrayList<>();
		for (List<String> r : rows) out.add(new ArrayList<>(r));
		Csv.write(file, header, out, Csv.eolOf(file));
	}

	private void writeReport(List<Finding> all) throws IOException {
		StringBuilder md = new StringBuilder("# AI 교차검토 (Gemini)\n\n");
		md.append("Claude가 만든 확인 대기 제안을 Gemini에게 독립적으로 물어 비교했다. 일치한 것은 `confirmed=AI`로 표시해 사용한다(사람 확인 `O`와 구분).\n\n");
		md.append("| 항목 | 검토 | 일치 | 불일치(사람 확인 필요) |\n| --- | ---: | ---: | ---: |\n");
		for (String type : List.of("식품 연결", "대체 규칙", "개당 중량")) {
			long n = all.stream().filter(f -> f.type().equals(type)).count();
			long ok = all.stream().filter(f -> f.type().equals(type) && f.agree()).count();
			md.append("| ").append(type).append(" | ").append(n).append(" | ").append(ok).append(" | ").append(n - ok).append(" |\n");
		}
		md.append("\n## 사람이 확인할 것 (불일치)\n\n| 항목 | 대상 | Claude 제안 | Gemini 답 | Gemini 이유 |\n| --- | --- | --- | --- | --- |\n");
		all.stream().filter(f -> !f.agree()).forEach(f -> md.append("| ").append(f.type()).append(" | ").append(esc(f.item())).append(" | ")
				.append(esc(f.proposal())).append(" | ").append(esc(f.aiAnswer())).append(" | ").append(esc(f.reason())).append(" |\n"));
		md.append("\n확인 방법: 해당 CSV(`data/mapping/`)에서 맞는 쪽으로 고치고 `confirmed`에 `O`를 넣는다.\n");
		Files.createDirectories(reportDir);
		Files.writeString(reportDir.resolve("ai-review.md"), md);
		System.out.println("AI 교차검토 완료: " + all.stream().filter(Finding::agree).count() + "/" + all.size() + " 일치 → "
				+ reportDir.resolve("ai-review.md").toAbsolutePath());
	}

	private static String esc(String s) {
		return s == null ? "" : s.replace("|", "/").replace("\n", " ");
	}

	private static String sha(String s) {
		try {
			byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(d, 0, 12);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
