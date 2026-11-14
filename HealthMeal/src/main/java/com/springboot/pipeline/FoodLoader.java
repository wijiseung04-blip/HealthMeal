package com.springboot.pipeline;

import com.springboot.pipeline.FoodRecord.Nutrient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 식품 원본을 foods / nutrition_definitions / food_nutrients 에 적재한다.
 * 다시 실행하면 기존 식품 데이터를 지우고 새로 넣는다(약 35만 행이라 JDBC 일괄 입력).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FoodLoader {

	private final JdbcTemplate jdbc;

	@Transactional
	public int load() throws IOException {
		return load(FoodSources.load());
	}

	/** 테스트용: 원본 위치 지정 */
	@Transactional
	public int load(java.nio.file.Path detailDir, java.nio.file.Path supplementCsv) throws IOException {
		return load(FoodSources.load(detailDir, supplementCsv));
	}

	private int load(FoodSources.Loaded loaded) {
		List<FoodRecord> foods = loaded.all();
		String version = LocalDate.now().toString();

		// 레시피가 이미 적재돼 있으면 재료→식품 연결을 먼저 끊는다. 식품 id가 바뀌므로 load-recipes로 다시 연결해야 한다
		int unlinked = jdbc.update("UPDATE recipe_ingredients SET food_id = NULL WHERE food_id IS NOT NULL");
		jdbc.update("DELETE FROM food_nutrients");
		jdbc.update("DELETE FROM foods");
		jdbc.update("DELETE FROM nutrition_definitions");

		// 영양소 정의: 처음 나온 순서대로
		Map<String, Nutrient> defs = new LinkedHashMap<>();
		for (FoodRecord f : foods) for (Nutrient n : f.nutrients()) defs.putIfAbsent(n.name(), n);
		List<Object[]> defRows = new ArrayList<>();
		int order = 0;
		for (Nutrient n : defs.values()) defRows.add(new Object[]{n.name(), n.engName(), n.unit(), order++});
		jdbc.batchUpdate("INSERT INTO nutrition_definitions (code, eng_name, unit, display_order) VALUES (?, ?, ?, ?)", defRows);

		List<Object[]> foodRows = new ArrayList<>();
		for (FoodRecord f : foods) {
			foodRows.add(new Object[]{f.code(), f.name(), f.baseName(), f.group(), f.origin(), f.examYear(), version});
		}
		// 물은 식품 DB에 없지만 레시피에 자주 나오므로 영양성분 0인 가상 식품으로 둔다
		foodRows.add(new Object[]{FoodMatcher.WATER_CODE, "물", "물", "가상", "HealthMeal", null, version});
		jdbc.batchUpdate("INSERT INTO foods (code, name, base_name, group_name, origin, exam_year, source_version) VALUES (?, ?, ?, ?, ?, ?, ?)", foodRows);

		Map<String, Long> ids = new HashMap<>();
		jdbc.query("SELECT id, code FROM foods", rs -> {
			ids.put(rs.getString("code"), rs.getLong("id"));
		});

		List<Object[]> nutrientRows = new ArrayList<>();
		for (FoodRecord f : foods) {
			Set<String> seen = new HashSet<>();
			for (Nutrient n : f.nutrients()) {
				if (!seen.add(n.name())) continue; // 같은 영양소가 두 번 나오면 첫 값
				nutrientRows.add(new Object[]{ids.get(f.code()), n.name(), n.value(), n.type().name()});
			}
		}
		int[] types = {Types.BIGINT, Types.VARCHAR, Types.DOUBLE, Types.VARCHAR};
		for (int i = 0; i < nutrientRows.size(); i += 5000) {
			jdbc.batchUpdate("INSERT INTO food_nutrients (food_id, nutrient_code, content, value_type) VALUES (?, ?, ?, ?)",
					nutrientRows.subList(i, Math.min(i + 5000, nutrientRows.size())), types);
		}

		Timestamp now = Timestamp.valueOf(LocalDateTime.now());
		jdbc.update("INSERT INTO data_versions (source, version, record_count, loaded_at) VALUES (?, ?, ?, ?)",
				FoodSources.API_SOURCE, version, loaded.api().size(), now);
		if (!loaded.supplement().isEmpty()) {
			jdbc.update("INSERT INTO data_versions (source, version, record_count, loaded_at) VALUES (?, ?, ?, ?)",
					FoodSources.SUPPLEMENT_SOURCE, version, loaded.supplement().size(), now);
		}
		log.info("식품 적재 완료: 국가표준 {}개 + 수산물 보충 {}개, 영양소 정의 {}개, 영양소 값 {}행",
				loaded.api().size(), loaded.supplement().size(), defs.size(), nutrientRows.size());
		if (unlinked > 0) log.warn("레시피 재료 {}개의 식품 연결을 해제했습니다. 이어서 load-recipes를 실행하세요.", unlinked);
		return foods.size();
	}
}
