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
}
