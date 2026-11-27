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
 *       ./gradlew importData --args="ai-translate recipe"     (recipe / ingredient / meal / food / step / tip 중 일부만)
 * 조리 순서(step)·저염 팁(tip)은 양이 많아 맨 뒤에 번역한다. 번역이 없는 문장은 화면에 원문으로 보인다
 */
final class AiTranslate {

	static final Path FILE = Path.of("data", "mapping", "translations-en.csv");
	static final List<String> TYPES = List.of("recipe", "ingredient", "meal", "food", "step", "tip"); // 화면에 많이 보이는 순서
	/** 한 번에 보내는 이름 수. 무료 등급은 하루 요청 수가 적어(모델에 따라 20회) 한 번에 많이 보낸다. GEMINI_TRANSLATE_BATCH로 바꿀 수 있다 */
	static final int BATCH = batchSize();

	private static int batchSize() {
		try {
			String v = System.getenv("GEMINI_TRANSLATE_BATCH");
			return v == null || v.isBlank() ? 250 : Math.max(10, Integer.parseInt(v.trim()));
		} catch (NumberFormatException e) {
			return 250;
		}
	}

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
		try {
			new AiTranslate(gemini, FILE).translate(sources(types));
		} catch (GeminiClient.DailyQuotaException | GeminiClient.BusyException e) {
			// 지금까지 번역한 것은 이미 파일에 저장됐다
			System.out.println("\n중단: " + e.getMessage());
			System.out.println("저장된 번역: " + FILE.toAbsolutePath() + " — 같은 명령을 다시 실행하면 남은 것만 번역한다");
		}
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
				case "step" -> RecipeRaw.load(Collector.RAW.resolve("cookrcp01")).forEach(r -> {
					for (int i = 1; i <= 20; i++) {
						String v = r.path("MANUAL%02d".formatted(i)).asString("").trim();
						if (!v.isEmpty()) names.add(v.replace("\n", " ").trim()); // RecipeLoader가 저장하는 형태와 같게
					}
				});
				case "tip" -> RecipeRaw.load(Collector.RAW.resolve("cookrcp01")).forEach(r -> names.add(r.path("RCP_NA_TIP").asString("").trim()));
				default -> throw new IllegalArgumentException("모르는 종류: " + type + " (recipe, ingredient, meal, food, step, tip)");
			}
			names.removeIf(n -> n.isBlank() || n.chars().noneMatch(c -> c >= '가' && c <= '힣'));
			out.put(type, names);
		}
		return out;
	}

	void translate(Map<String, Set<String>> sources) throws IOException, InterruptedException {
		Map<String, List<String>> rows = load(); // ko → [type, ko, en]
		long left = sources.values().stream().flatMap(Set::stream).filter(n -> !rows.containsKey(n)).count();
		System.out.printf("  남은 이름 %d개 → 요청 약 %d회 (한 번에 %d개)%n", left, (left + BATCH - 1) / BATCH, BATCH);
		for (var e : sources.entrySet()) {
			List<String> todo = e.getValue().stream().filter(n -> !rows.containsKey(n)).toList();
			System.out.printf("  %s: %d개 중 %d개 번역 필요%n", e.getKey(), e.getValue().size(), todo.size());
			// 답이 길어 잘리면(긴 제품 이름) 받은 만큼 쓰고, 남은 것은 묶음을 반으로 줄여 다시 보낸다
			Deque<String> pending = new ArrayDeque<>(todo);
			int size = BATCH, done = 0;
			while (!pending.isEmpty()) {
				List<String> batch = new ArrayList<>();
				for (Iterator<String> it = pending.iterator(); it.hasNext() && batch.size() < size; ) batch.add(it.next());
				Answer a;
				try {
					a = ask(e.getKey(), batch);
				} catch (GeminiClient.BusyException busy) {
					// 큰 묶음은 처리가 오래 걸려 시간 초과가 잘 난다: 줄여서 한 번 더, 최소 묶음에서도 안 되면 멈춘다(저장된 것은 유지)
					if (size <= MIN_BATCH) throw busy;
					size = Math.max(MIN_BATCH, size / 2);
					System.out.printf("    혼잡·시간 초과 → 이제 %d개씩%n", size);
					continue;
				}
				int got = 0;
				for (int k = 0; k < batch.size(); k++) {
					String en = a.byId().get(k);
					if (en != null && !en.isBlank()) {
						rows.put(batch.get(k), List.of(e.getKey(), batch.get(k), en.strip()));
						pending.remove(batch.get(k));
						got++;
					}
				}
				save(rows); // 묶음마다 저장: 한도로 끊겨도 다음 실행에서 이어서
				done += got;
				if (a.truncated() && size > MIN_BATCH) {
					size = Math.max(MIN_BATCH, size / 2);
					System.out.printf("    답이 잘림: %d개 중 %d개만 받음 → 이제 %d개씩%n", batch.size(), got, size);
				} else {
					batch.forEach(pending::remove); // 끝까지 받았는데 빠진 이름(또는 최소 묶음에서도 잘림)은 다음 실행으로
				}
				System.out.printf("    %d/%d%n", done, todo.size());
			}
		}
		System.out.println("번역 파일: " + file.toAbsolutePath() + " (" + rows.size() + "개). 앱을 다시 시작하면 영어 화면에 쓰인다");
	}

	static String prompt(String type, List<String> names) {
		if (type.equals("step") || type.equals("tip")) {
			StringBuilder p = new StringBuilder("""
					Translate these Korean recipe %s into clear, natural English for a cooking app.
					- Translate each item as a whole; keep numbers, units, times, temperatures and step references like (1) or (2).
					- Ignore stray single letters at the end (e.g. "…찐다.c" → "…steam it.").
					- Do not add explanations.
					Answer only a JSON array: [{"id": number, "en": "English"}]

					""".formatted(type.equals("step") ? "step instructions" : "low-sodium cooking tips"));
			for (int i = 0; i < names.size(); i++) p.append("id ").append(i).append(": ").append(names.get(i)).append('\n');
			return p.toString();
		}
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

	static final int MIN_BATCH = 30;

	/** truncated: 답(JSON)이 중간에 끊김 — 완성된 항목만 byId에 담는다 */
	record Answer(Map<Integer, String> byId, boolean truncated) {
	}

	private static final java.util.regex.Pattern ITEM =
			java.util.regex.Pattern.compile("\\{\\s*\"id\"\\s*:\\s*(\\d+)\\s*,\\s*\"en\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*}");

	private Answer ask(String type, List<String> names) throws IOException, InterruptedException {
		return parse(ai.ask(prompt(type, names)));
	}

	/** 정상 JSON이면 그대로, 응답 길이 한도로 끊긴 JSON이면 끝까지 닫힌 {"id":…,"en":…}만 살린다 */
	Answer parse(String answer) {
		String body = answer.replaceAll("(?s)^```(json)?|```$", "").trim();
		Map<Integer, String> out = new HashMap<>();
		try {
			for (JsonNode n : json.readTree(body)) out.put(n.path("id").asInt(-1), n.path("en").asString(""));
			return new Answer(out, false);
		} catch (RuntimeException broken) {
			java.util.regex.Matcher m = ITEM.matcher(body);
			while (m.find()) {
				try {
					out.put(Integer.parseInt(m.group(1)), json.readTree("\"" + m.group(2) + "\"").asString());
				} catch (RuntimeException ignored) {
					// 이 항목만 버린다
				}
			}
			return new Answer(out, true);
		}
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
