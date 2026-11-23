package com.springboot.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class AiTranslateTest {

	@Test
	void 번역을_묶음으로_쌓고_이미_한_이름은_다시_묻지_않는다(@TempDir Path dir) throws Exception {
		List<String> prompts = new ArrayList<>();
		AiReview.Ai fake = prompt -> {
			prompts.add(prompt);
			StringBuilder sb = new StringBuilder("[");
			int id = 0;
			for (String line : prompt.split("\n")) {
				if (!line.startsWith("id ")) continue;
				if (id > 0) sb.append(',');
				sb.append("{\"id\":").append(id++).append(",\"en\":\"EN ").append(line.substring(line.indexOf(": ") + 2)).append("\"}");
			}
			return sb.append(']').toString();
		};
		Path file = dir.resolve("translations-en.csv");
		var t = new AiTranslate(fake, file);
		Map<String, Set<String>> src = new LinkedHashMap<>();
		src.put("recipe", new LinkedHashSet<>(List.of("된장국", "새우 두부 계란찜")));
		t.translate(src);
		assertThat(prompts).hasSize(1);
		assertThat(prompts.getFirst()).contains("id 0: 된장국").contains("JSON array").contains("Pork, tenderloin, raw");
		assertThat(Csv.read(file)).extracting(r -> r.get(2)).containsExactly("EN 된장국", "EN 새우 두부 계란찜");

		src.get("recipe").add("미역국");
		t.translate(src);
		assertThat(prompts).hasSize(2);
		assertThat(prompts.get(1)).contains("id 0: 미역국").doesNotContain("된장국");
		assertThat(Csv.read(file)).hasSize(3);
	}

	@Test
	void 번역할_이름을_종류별로_모은다() throws Exception {
		var s = AiTranslate.sources(new LinkedHashSet<>(List.of("recipe", "meal")));
		assertThat(s.get("recipe")).hasSizeGreaterThan(1000).contains("새우 두부 계란찜");
		assertThat(s.get("meal")).hasSizeGreaterThan(500);
		assertThat(s).doesNotContainKey("food");
	}

	@Test
	void 답이_잘리면_받은_만큼_저장하고_묶음을_줄여_나머지를_다시_보낸다(@TempDir Path dir) throws Exception {
		List<Integer> sizes = new ArrayList<>();
		AiReview.Ai fake = prompt -> {
			List<String> names = new ArrayList<>();
			for (String line : prompt.split("\n")) if (line.startsWith("id ")) names.add(line.substring(line.indexOf(": ") + 2));
			sizes.add(names.size());
			StringBuilder sb = new StringBuilder("[");
			int limit = names.size() > 40 ? names.size() / 3 : names.size(); // 큰 묶음은 3분의 1에서 잘린다
			for (int i = 0; i < limit; i++) sb.append(i > 0 ? "," : "").append("{\"id\":").append(i).append(",\"en\":\"EN ").append(names.get(i)).append("\"}");
			return limit < names.size() ? sb.append(",{\"id\":").append(limit).append(",\"en\":\"잘").toString() : sb.append("]").toString();
		};
		Path file = dir.resolve("translations-en.csv");
		Set<String> meals = new LinkedHashSet<>();
		for (int i = 0; i < 100; i++) meals.add("메뉴" + i);
		new AiTranslate(fake, file).translate(new LinkedHashMap<>(Map.of("meal", meals)));
		assertThat(Csv.read(file)).hasSize(100);
		assertThat(sizes.getFirst()).isEqualTo(AiTranslate.BATCH > 100 ? 100 : AiTranslate.BATCH);
		assertThat(sizes).anyMatch(n -> n <= 50); // 잘린 뒤 묶음이 줄었다
	}

	@Test
	void 잘린_JSON에서_완성된_항목만_살린다() {
		var a = new AiTranslate(p -> "", Path.of("x")).parse("[{\"id\":0,\"en\":\"Rice \\\"bowl\\\"\"},{\"id\":1,\"en\":\"Kim");
		assertThat(a.truncated()).isTrue();
		assertThat(a.byId()).containsOnlyKeys(0).containsEntry(0, "Rice \"bowl\"");
	}

	@Test
	void 혼잡하면_묶음을_줄여_계속하고_최소_묶음에서도_안_되면_저장된_것은_남기고_멈춘다(@TempDir Path dir) throws Exception {
		List<Integer> sizes = new ArrayList<>();
		AiReview.Ai fake = prompt -> {
			List<String> names = new ArrayList<>();
			for (String line : prompt.split("\n")) if (line.startsWith("id ")) names.add(line.substring(line.indexOf(": ") + 2));
			sizes.add(names.size());
			if (names.size() > 60) throw new GeminiClient.BusyException("응답 시간 초과"); // 큰 묶음만 시간 초과
			StringBuilder sb = new StringBuilder("[");
			for (int i = 0; i < names.size(); i++) sb.append(i > 0 ? "," : "").append("{\"id\":").append(i).append(",\"en\":\"EN\"}");
			return sb.append("]").toString();
		};
		Path file = dir.resolve("translations-en.csv");
		Set<String> meals = new LinkedHashSet<>();
		for (int i = 0; i < 200; i++) meals.add("메뉴" + i);
		new AiTranslate(fake, file).translate(new LinkedHashMap<>(Map.of("meal", meals)));
		assertThat(Csv.read(file)).hasSize(200);
		assertThat(sizes).contains(AiTranslate.MIN_BATCH > 60 ? AiTranslate.MIN_BATCH : 62);

		AiReview.Ai alwaysBusy = prompt -> { throw new GeminiClient.BusyException("오류 503가 계속됨"); };
		Set<String> more = new LinkedHashSet<>(List.of("새메뉴"));
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AiTranslate(alwaysBusy, file).translate(new LinkedHashMap<>(Map.of("meal", more))))
				.isInstanceOf(GeminiClient.BusyException.class);
		assertThat(Csv.read(file)).hasSize(200); // 이미 번역한 것은 그대로
	}
}
