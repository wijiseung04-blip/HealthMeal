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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
	@Autowired com.springboot.service.SubstitutionService substitutionService;

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

	@Test
	void 비회원도_레시피를_검색한다() throws Exception {
		mvc.perform(get("/recipes"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("1156개 레시피")));
		mvc.perform(get("/recipes").param("q", "무대하"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("무대하무침")))
				.andExpect(content().string(containsString("1개 레시피")));
		mvc.perform(get("/recipes").param("category", "국&찌개"))
				.andExpect(status().isOk())
				.andExpect(content().string(not(containsString("1156개 레시피"))));
	}

	@Test
	void 레시피_상세에_영양정보_재료_조리순서가_나온다() throws Exception {
		Long id = jdbc.queryForObject("SELECT id FROM recipes WHERE seq = '720'", Long.class);
		mvc.perform(get("/recipes/" + id))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("무대하무침")))
				.andExpect(content().string(containsString("나트륨")))
				.andExpect(content().string(containsString("통깨 0.2g")))
				.andExpect(content().string(containsString("모든 재료")))
				.andExpect(content().string(not(containsString("실제보다 낮을 수 있습니다"))));
	}

	@Test
	void 미확인_재료가_있으면_하한값_안내가_나온다() throws Exception {
		Long id = jdbc.queryForObject("SELECT id FROM recipes WHERE seq = '927'", Long.class);
		mvc.perform(get("/recipes/" + id))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("실제보다 낮을 수 있습니다")))
				.andExpect(content().string(containsString("소금 적당량")));
	}

	@Test
	void 없는_레시피는_404_화면이다() throws Exception {
		mvc.perform(get("/recipes/999999")).andExpect(status().isNotFound());
	}

	private Long recipeId(String seq) {
		return jdbc.queryForObject("SELECT id FROM recipes WHERE seq = ?", Long.class, seq);
	}

	@Test
	void 저나트륨_목표면_조미료를_감량하고_계산으로_판정한다() {
		// 무대하무침: 모든 재료 계산됨, 간장 2g(조미료류)
		var s = substitutionService.suggest(recipeId("720"), com.springboot.domain.NutritionGoal.LOW_SODIUM).orElseThrow();
		var soy50 = s.options().stream().filter(o -> o.ingredient().equals("간장 2g") && o.ratio() == 0.5).findFirst().orElseThrow();

		assertThat(soy50.type()).isEqualTo(com.springboot.service.SubstitutionService.ChangeType.REDUCE);
		assertThat(soy50.gramsAfter()).isEqualTo(1.0);
		assertThat(soy50.goalComparison().delta()).isNegative();
		assertThat(soy50.goalComparison().rateKind()).isEqualTo(NutritionEngine.RateKind.EXACT);
		// 모든 후보는 나트륨을 실제로 줄인다
		assertThat(s.options()).allSatisfy(o -> assertThat(o.goalComparison().delta()).isNegative());
	}

	@Test
	void 검수_전_대체_규칙은_추천하지_않고_검수_대기로_안내한다() {
		var s = substitutionService.suggest(recipeId("720"), com.springboot.domain.NutritionGoal.LOW_CALORIE).orElseThrow();
		assertThat(s.options()).noneMatch(o -> o.type() == com.springboot.service.SubstitutionService.ChangeType.SUBSTITUTE);
		assertThat(s.pending()).extracting(p -> p.description()).anyMatch(d -> d.contains("올리고당"));
	}

	@Test
	void 검수된_대체_규칙은_계산해서_추천한다() {
		jdbc.update("UPDATE substitute_pool SET confirmed = TRUE WHERE source_food_code = 'C0110020009a'");
		try {
			var s = substitutionService.suggest(recipeId("720"), com.springboot.domain.NutritionGoal.LOW_CALORIE).orElseThrow();
			var sugar = s.options().stream().filter(o -> o.ingredient().equals("설탕 1g")).findFirst().orElseThrow();
			assertThat(sugar.candidateFoodName()).isEqualTo("시럽, 올리고당");
			assertThat(sugar.goalComparison().delta()).isNegative();
			// 설탕 1g의 변화는 전체 에너지의 10%에 못 미친다
			assertThat(sugar.verdict()).isEqualTo(com.springboot.service.SubstitutionService.Verdict.NOT_ENOUGH);
		} finally {
			jdbc.update("UPDATE substitute_pool SET confirmed = FALSE");
		}
	}

	@Test
	void 조절_화면과_비교_화면이_나온다() throws Exception {
		Long id = recipeId("720");
		var option = substitutionService.suggest(id, com.springboot.domain.NutritionGoal.LOW_SODIUM).orElseThrow().options().getFirst();
		mvc.perform(get("/recipes/" + id + "/adjust").param("goal", "LOW_SODIUM"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("감량")));
		mvc.perform(get("/recipes/" + id + "/adjust").param("goal", "LOW_SODIUM").param("option", option.id()))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("대체 전")))
				.andExpect(content().string(containsString("바뀐 재료 목록")));
		mvc.perform(get("/recipes/" + id + "/adjust").param("goal", "NO_SUCH_GOAL")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/recipes/" + id + "/substitutions").param("goal", "LOW_SODIUM"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.options[0].goalComparison.delta").isNumber());
	}
}
