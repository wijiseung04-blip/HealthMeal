package com.springboot.pipeline;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** data/raw/cookrcp01 원본 JSON에서 레시피 행을 읽는다. */
final class RecipeRaw {

	private RecipeRaw() {
	}

	static List<JsonNode> load(Path dir) throws IOException {
		JsonMapper mapper = JsonMapper.builder().build();
		List<JsonNode> rows = new ArrayList<>();
		try (Stream<Path> files = Files.list(dir)) {
			for (Path f : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
				mapper.readTree(f.toFile()).path("COOKRCP01").path("row").forEach(rows::add);
			}
		}
		if (rows.isEmpty()) throw new IllegalStateException(dir + "에 레시피 원본이 없습니다. 먼저 collect recipes를 실행하세요.");
		return rows;
	}
}
