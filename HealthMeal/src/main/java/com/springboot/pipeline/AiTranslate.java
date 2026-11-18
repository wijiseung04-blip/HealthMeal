package com.springboot.pipeline;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * 공공 데이터 이름(레시피·재료·식품·식사 후보)을 Gemini로 영어 번역해 data/mapping/translations-en.csv 에 쌓는다 (영어 화면용, 8장 다국어 확장).
 * 공개 데이터만 보낸다(회원·검진 정보 없음). 이미 번역한 이름은 건너뛰고, 묶음마다 파일에 저장하므로 무료 한도로 끊겨도 다시 실행하면 이어서 한다.
 * 사용: ./gradlew importData --args="ai-translate"            (전체)
 *       ./gradlew importData --args="ai-translate recipe"     (recipe / ingredient / meal / food 중 일부만)
 * 조리 순서 문장은 양이 많아 번역하지 않는다 (화면에 원문 표시)
 */
final class AiTranslate {

	static final Path FILE = Path.of("data", "mapping", "translations-en.csv");
	static final List<String> TYPES = List.of("recipe", "ingredient", "meal", "food"); // 화면에 많이 보이는 순서
	static final int BATCH = 60;

	private final AiReview.Ai ai;
	private final Path file;
	private final JsonMapper json = JsonMapper.builder().build();

	AiTranslate(AiReview.Ai ai, Path file) {
		this.ai = ai;
		this.file = file;
	}

	static void run(String[] args) throws Exception {
		Set<String> types = new LinkedHashSet<>(args.length > 1 ? Arrays.asList(Arrays.copyOfRange(args, 1, args.length)) : TYPES);
		GeminiClient gemini = GeminiClient.fromEnv();
		System.out.println("AI 번역 (" + gemini.model() + "): " + types);
		new AiTranslate(gemini, FILE).translate(sources(types));
	}

	/** 종류별 번역할 이름 (중복 제거, 순서 유지) */
	static Map<String, Set<String>> sources(Set<String> types) throws IOException {
		Map<String, Set<String>> out = new LinkedHashMap<>();
		for (String type : TYPES) {
			if (!types.contains(type)) continue;
			Set<String> names = new LinkedHashSet<>();
			switch (type) {
				case "recipe" -> RecipeRaw.load(Collector.RAW.resolve("cookrcp01"))
						.forEach(r -> names.add(r.path("RCP_NM").asString("").trim()));
				case "ingredient" -> Csv.read(Path.of("data", "parsed", "recipe-ingredients.csv")).forEach(r -> {
					if (r.size() > 4) names.add(r.get(4).trim());
				});
				case "food" -> Csv.read(Path.of("data", "parsed", "foods.csv")).forEach(r -> {
					if (r.size() > 1) names.add(r.get(1).trim());
				});
				case "meal" -> {
					List<Map<String, String>> dishes = MealItemLoader.read(MealItemLoader.DIR.resolve("dishes.csv"));
					MealItemLoader.eatingOut(dishes).forEach(i -> names.add(i.name()));
					MealItemLoader.convenience(MealItemLoader.read(MealItemLoader.DIR.resolve("processed-foods.csv"))).forEach(i -> names.add(i.name()));
				}
				default -> throw new IllegalArgumentException("모르는 종류: " + type + " (recipe, ingredient, meal, food)");
			}
			names.removeIf(n -> n.isBlank() || n.chars().noneMatch(c -> c >= '가' && c <= '힣'));
			out.put(type, names);
		}
		return out;
	}

	void translate(Map<String, Set<String>> sources) throws IOException, InterruptedException {
		Map<String, List<String>> rows = load(); // ko → [type, ko, en]
		for (var e : sources.entrySet()) {
			List<String> todo = e.getValue().stream().filter(n -> !rows.containsKey(n)).toList();
			System.out.printf("  %s: %d개 중 %d개 번역 필요%n", e.getKey(), e.getValue().size(), todo.size());
			for (int i = 0; i < todo.size(); i += BATCH) {
				List<String> batch = todo.subList(i, Math.min(todo.size(), i + BATCH));
				Map<Integer, String> answers = ask(e.getKey(), batch);
				for (int k = 0; k < batch.size(); k++) {
					String en = answers.get(k);
					if (en != null && !en.isBlank()) rows.put(batch.get(k), List.of(e.getKey(), batch.get(k), en.strip()));
				}
				save(rows); // 묶음마다 저장: 한도로 끊겨도 다음 실행에서 이어서
				System.out.printf("    %d/%d%n", Math.min(todo.size(), i + BATCH), todo.size());
			}
		}
		System.out.println("번역 파일: " + file.toAbsolutePath() + " (" + rows.size() + "개). 앱을 다시 시작하면 영어 화면에 쓰인다");
	}

	static String prompt(String type, List<String> names) {
		StringBuilder p = new StringBuilder("""
				Translate these Korean %s names into short, natural English for a nutrition app (English UI).
				- Keep numbers, units and brackets. Do not add explanations.
				- Food composition names use commas, e.g. "돼지고기, 안심, 생것" → "Pork, tenderloin, raw". Keep that comma structure.
				- For packaged products, translate descriptive words and romanize brand/product names.
				- Korean dishes with well-known names: use the romanized name with a short English hint, e.g. "김치찌개" → "Kimchi-jjigae (kimchi stew)".
				Answer only a JSON array: [{"id": number, "en": "English"}]

				""".formatted(switch (type) {
			case "recipe" -> "recipe";
			case "ingredient" -> "recipe ingredient";
			case "meal" -> "dish or packaged meal";
			default -> "food composition database";
		}));
		for (int i = 0; i < names.size(); i++) p.append("id ").append(i).append(": ").append(names.get(i)).append('\n');
		return p.toString();
	}

	private Map<Integer, String> ask(String type, List<String> names) throws IOException, InterruptedException {
		String answer = ai.ask(prompt(type, names));
		Map<Integer, String> out = new HashMap<>();
		JsonNode root = json.readTree(answer.replaceAll("(?s)^```(json)?|```$", "").trim());
		for (JsonNode n : root) out.put(n.path("id").asInt(-1), n.path("en").asString(""));
		return out;
	}

	private Map<String, List<String>> load() throws IOException {
		Map<String, List<String>> rows = new LinkedHashMap<>();
		for (List<String> r : Csv.read(file)) if (r.size() >= 3 && !r.get(2).isBlank()) rows.put(r.get(1), r);
		return rows;
	}

	private void save(Map<String, List<String>> rows) throws IOException {
		List<List<Object>> out = new ArrayList<>();
		rows.values().forEach(r -> out.add(new ArrayList<>(r)));
		Csv.write(file, List.of("type", "ko", "en"), out);
	}
}
