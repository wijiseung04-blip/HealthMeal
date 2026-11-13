package com.springboot.pipeline;

import com.springboot.nutrition.NutritionEngine;
import com.springboot.service.RecipeNutritionService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 실제 원본 전체를 H2에 적재하고, DB 경로(API)와 오프라인 경로의 계산 결과가 같은지 확인한다 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecipePipelineIntegrationTest {

	@Autowired FoodLoader foodLoader;
	@Autowired RecipeLoader recipeLoader;
	@Autowired RecipeNutritionService service;
	@Autowired JdbcTemplate jdbc;
	@Autowired MockMvc mvc;

	@BeforeAll
	void load() throws Exception {
		foodLoader.load(Collector.RAW.resolve("food-detail"));
		recipeLoader.load();
	}

	@Test
	void 전체_원본이_적재된다() {
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM foods", Integer.class)).isEqualTo(2766); // + 물
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recipes", Integer.class)).isEqualTo(1156);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recipe_ingredients", Integer.class)).isEqualTo(13138);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recipe_steps", Integer.class)).isPositive();
	}

	@Test
	void DB_경로와_오프라인_경로의_계산이_같다() throws Exception {
		List<FoodRecord> foods = FoodDetailParser.loadAll(Collector.RAW.resolve("food-detail"));
		var offline = IngredientResolver.resolveAll(foods);
		var source = NutritionCheck.source(foods);
		for (String seq : List.of("720", "3764", "219", "927")) {
			var data = offline.stream().filter(d -> d.raw().path("RCP_SEQ").asString("").equals(seq)).findFirst().orElseThrow();
			var expected = NutritionEngine.calculate(NutritionCheck.toItems(data.ingredients()), NutritionEngine.KEY_NUTRIENTS, source);

			Long id = jdbc.queryForObject("SELECT id FROM recipes WHERE seq = ?", Long.class, seq);
			var actual = service.calculate(id).orElseThrow().result();
			for (String n : NutritionEngine.KEY_NUTRIENTS) {
				assertThat(actual.totals().get(n)).as(seq + " " + n).isCloseTo(expected.totals().get(n), within(1e-6));
				assertThat(actual.coverage(n)).as(seq + " " + n).isEqualTo(expected.coverage(n));
			}
		}
	}

	@Test
	void 비회원도_영양_계산_API를_조회한다() throws Exception {
		Long id = jdbc.queryForObject("SELECT id FROM recipes WHERE seq = '720'", Long.class);
		mvc.perform(get("/api/recipes/" + id + "/nutrition"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.recipeName").value("무대하무침"))
				.andExpect(jsonPath("$.result.totals.나트륨").isNumber())
				.andExpect(jsonPath("$.result.coverage.totalItems").value(9));
		mvc.perform(get("/api/recipes/999999/nutrition")).andExpect(status().isNotFound());
	}
}
