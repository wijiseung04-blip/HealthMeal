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
	@Autowired com.springboot.service.MemberService memberService;
	@Autowired com.springboot.repository.SavedRecipeRepository savedRepository;
	@Autowired MealItemLoader mealItemLoader;
	@Autowired com.springboot.service.MealPlanService mealPlanService;

	@BeforeAll
	void load() throws Exception {
		foodLoader.load();
		recipeLoader.load();
	}

	@Test
	void 전체_원본이_적재된다() {
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM foods", Integer.class)).isEqualTo(3821); // 국가표준 2765 + 수산물 보충 1055 + 물
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recipes WHERE owner_id IS NULL", Integer.class)).isEqualTo(1156);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recipe_ingredients ri JOIN recipes r ON r.id = ri.recipe_id WHERE r.owner_id IS NULL",
				Integer.class)).isEqualTo(13138);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recipe_steps", Integer.class)).isPositive();
	}

	@Test
	void DB_경로와_오프라인_경로의_계산이_같다() throws Exception {
		List<FoodRecord> foods = FoodSources.loadAll();
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
		// 실제 데이터에는 AI 교차검토로 확정된 규칙이 있으므로, 모두 확인 전인 상태를 만들어 확인한다
		jdbc.update("UPDATE substitute_pool SET confirmed = FALSE");
		var s = substitutionService.suggest(recipeId("720"), com.springboot.domain.NutritionGoal.LOW_CALORIE).orElseThrow();
		assertThat(s.options()).noneMatch(o -> o.type() == com.springboot.service.SubstitutionService.ChangeType.SUBSTITUTE);
		assertThat(s.pending()).extracting(p -> p.description()).anyMatch(d -> d.contains("올리고당"));
	}

	@Test
	void 검수된_대체_규칙은_계산해서_추천한다() {
		jdbc.update("UPDATE substitute_pool SET confirmed = TRUE WHERE source_food_code = 'C0110020009a'");
		try {
			var s = substitutionService.suggest(recipeId("720"), com.springboot.domain.NutritionGoal.LOW_CALORIE).orElseThrow();
			var sugar = s.options().stream()
					.filter(o -> o.ingredient().equals("설탕 1g") && o.type() == com.springboot.service.SubstitutionService.ChangeType.SUBSTITUTE)
					.findFirst().orElseThrow(); // 같은 설탕에 맛 내기 감량 후보도 있다
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

	@Test
	void 레시피와_맞춤_레시피를_CSV로_내려받는다() throws Exception {
		Long id = recipeId("720");
		mvc.perform(get("/recipes/" + id + "/download"))
				.andExpect(status().isOk())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Disposition", containsString("attachment")))
				.andExpect(content().string(containsString("무대하무침")))
				.andExpect(content().string(containsString("간장 2g")))
				.andExpect(content().string(containsString("나트륨")));

		var option = substitutionService.suggest(id, com.springboot.domain.NutritionGoal.LOW_SODIUM).orElseThrow().options().getFirst();
		mvc.perform(get("/recipes/" + id + "/download").param("goal", "LOW_SODIUM").param("option", option.id()))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("적용한 변경")))
				.andExpect(content().string(containsString("변경 후")));
		mvc.perform(get("/recipes/999999/download")).andExpect(status().isNotFound());
	}

	private String newMember() {
		var f = new com.springboot.dto.SignupForm();
		f.setUsername("u" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12));
		f.setName("회원");
		f.setPassword("password123");
		f.setPasswordConfirm("password123");
		memberService.signup(f);
		return f.getUsername();
	}

	@Test
	void 맞춤_레시피를_저장하고_다시_적재해도_이력이_레시피를_찾는다() throws Exception {
		String username = newMember();
		Long id = recipeId("720");
		String option = substitutionService.suggest(id, com.springboot.domain.NutritionGoal.LOW_SODIUM).orElseThrow().options().getFirst().id();
		var save = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/recipes/" + id + "/adjust/save")
				.param("goal", "LOW_SODIUM").param("option", option)
				.with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf());

		// 비회원은 로그인으로
		mvc.perform(save).andExpect(status().is3xxRedirection())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/login"));

		var asMember = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(username);
		mvc.perform(save.with(asMember)).andExpect(status().is3xxRedirection());
		mvc.perform(save.with(asMember)); // 같은 것을 다시 저장해도 하나만
		assertThat(savedRepository.findByMemberUsernameOrderBySavedAtDesc(username)).hasSize(1);

		mvc.perform(get("/mypage").with(asMember))
				.andExpect(content().string(containsString("저장한 맞춤 레시피")))
				.andExpect(content().string(containsString("무대하무침")))
				.andExpect(content().string(containsString("/recipes/" + id + "/adjust")));

		// 레시피를 다시 적재하면 id가 바뀌어도 원본 번호로 찾아간다
		recipeLoader.load();
		Long newId = recipeId("720");
		assertThat(newId).isNotEqualTo(id);
		mvc.perform(get("/mypage").with(asMember))
				.andExpect(content().string(containsString("/recipes/" + newId + "/adjust")));
		mvc.perform(get("/recipes/" + newId + "/adjust").param("goal", "LOW_SODIUM").param("option", option))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("대체 전")));
	}

	@Test
	void 다른_회원의_저장_이력은_지울_수_없다() throws Exception {
		String owner = newMember();
		String other = newMember();
		Long id = recipeId("720");
		String option = substitutionService.suggest(id, com.springboot.domain.NutritionGoal.LOW_SODIUM).orElseThrow().options().getFirst().id();
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/recipes/" + id + "/adjust/save")
				.param("goal", "LOW_SODIUM").param("option", option)
				.with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
				.with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(owner)));
		Long savedId = savedRepository.findByMemberUsernameOrderBySavedAtDesc(owner).getFirst().getId();

		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/mypage/saved/" + savedId + "/delete")
				.with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
				.with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(other)));
		assertThat(savedRepository.findById(savedId)).isPresent();
	}

	@Test
	void 없는_후보는_저장하지_않는다() throws Exception {
		String username = newMember();
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/recipes/" + recipeId("720") + "/adjust/save")
						.param("goal", "LOW_SODIUM").param("option", "999-REDUCE-0.5")
						.with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
						.with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(username)))
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash().attribute("savedMessage", "저장할 후보를 찾을 수 없습니다."));
		assertThat(savedRepository.findByMemberUsernameOrderBySavedAtDesc(username)).isEmpty();
	}

	@Test
	void 국가표준에_없는_수산물은_표준데이터로_계산하고_출처를_표시한다() throws Exception {
		assertThat(jdbc.queryForList("SELECT source FROM data_versions", String.class))
				.contains("국가표준식품성분정보", "원재료성식품 표준데이터(수산물)");
		mvc.perform(get("/recipes/" + recipeId("281"))) // 백태순두부찌개: 바지락(30g)
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("바지락, 생것")))
				.andExpect(content().string(containsString("(수산물 표준데이터)")));
	}

	@Test
	void 레시피가_적재된_상태에서도_식품을_다시_적재할_수_있다() throws Exception {
		foodLoader.load(); // 레시피 재료가 식품을 참조하고 있어도 실패하지 않는다
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recipe_ingredients WHERE food_id IS NOT NULL", Integer.class)).isZero();

		recipeLoader.load(); // 다시 연결
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recipe_ingredients WHERE food_id IS NOT NULL", Integer.class)).isPositive();
	}

	@Test
	void 볶음_기름은_조리_순서를_근거로_감량하고_튀김_기름은_감량하지_않는다() {
		// 유자삼치구이: "팬에 기름을 두르고 구워"
		var grill = substitutionService.suggest(recipeId("509"), com.springboot.domain.NutritionGoal.LOW_CALORIE).orElseThrow();
		assertThat(grill.options()).anySatisfy(o -> {
			assertThat(o.ingredient()).contains("식용유");
			assertThat(o.basis()).startsWith("볶음·부침용 기름 — “").contains("두르고");
		});
		// 황태탕수육: 튀김기름은 줄이면 튀김이 안 되므로 후보가 아니라 안내만
		var fried = substitutionService.suggest(recipeId("442"), com.springboot.domain.NutritionGoal.LOW_CALORIE).orElseThrow();
		assertThat(fried.options()).noneMatch(o -> o.ingredient().contains("튀김기름"));
		assertThat(fried.pending()).anySatisfy(p -> assertThat(p.description()).contains("튀김 기름"));
	}

	@Test
	void 개인_레시피를_등록하면_주인만_보고_영양_계산과_감량을_쓴다() throws Exception {
		var csrf = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf();
		String owner = newMember();
		String other = newMember();
		var asOwner = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(owner);
		var asOther = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(other);
		var post = (java.util.function.Function<String, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder>)
				org.springframework.test.web.servlet.request.MockMvcRequestBuilders::post;

		// 미리보기는 저장하지 않는다
		int before = jdbc.queryForObject("SELECT COUNT(*) FROM recipes", Integer.class);
		mvc.perform(post.apply("/myrecipes").with(csrf).with(asOwner).param("action", "preview")
						.param("name", "우리집테스트볶음").param("ingredients", "양파 100g\n외계과일 30g"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("재료 분석 결과")))
				.andExpect(content().string(containsString("양파, 생것")))
				.andExpect(content().string(containsString("식품 미확인")));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recipes", Integer.class)).isEqualTo(before);

		String location = mvc.perform(post.apply("/myrecipes").with(csrf).with(asOwner)
						.param("name", "우리집테스트볶음").param("category", "반찬")
						.param("ingredients", "양파 100g\n돼지고기 등심 150g\n간장 15g\n\n참기름 5g\n외계과일 30g")
						.param("steps", "1. 양파를 채 썬다.\n2. 팬에 참기름을 두르고 고기를 볶는다."))
				.andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
		Long id = Long.valueOf(location.replace("/recipes/", ""));

		// 주인: 상세(영양 계산·내 레시피 표시)와 감량 후보
		mvc.perform(get("/recipes/" + id).with(asOwner))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("내 레시피")))
				.andExpect(content().string(containsString("양파, 생것")))
				.andExpect(content().string(containsString("양파를 채 썬다")));
		var suggestions = substitutionService.suggest(id, com.springboot.domain.NutritionGoal.LOW_SODIUM).orElseThrow();
		assertThat(suggestions.options()).anySatisfy(o -> assertThat(o.ingredient()).contains("간장"));

		// 다른 회원·비회원에게는 없는 레시피, 검색에도 나오지 않는다
		mvc.perform(get("/recipes/" + id).with(asOther)).andExpect(status().isNotFound());
		mvc.perform(get("/recipes/" + id)).andExpect(status().isNotFound());
		mvc.perform(get("/recipes/" + id + "/adjust").with(asOther)).andExpect(status().isNotFound());
		mvc.perform(get("/recipes/" + id + "/download")).andExpect(status().isNotFound());
		mvc.perform(get("/api/recipes/" + id + "/nutrition")).andExpect(status().isNotFound());
		mvc.perform(get("/recipes").param("q", "우리집테스트볶음").with(asOwner))
				.andExpect(content().string(org.hamcrest.Matchers.not(containsString("href=\"/recipes/" + id + "\""))));
		mvc.perform(get("/mypage").with(asOwner)).andExpect(content().string(containsString("우리집테스트볶음")));

		// 공공 레시피를 다시 적재해도 남고, 재료가 다시 분석된다
		recipeLoader.load();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recipe_ingredients WHERE recipe_id = ? AND food_id IS NOT NULL", Integer.class, id))
				.isGreaterThanOrEqualTo(4);

		// 수정은 주인만, 삭제 후에는 없다
		mvc.perform(post.apply("/myrecipes/" + id).with(csrf).with(asOther).param("name", "남의것").param("ingredients", "물 100g"))
				.andExpect(status().isNotFound());
		mvc.perform(post.apply("/myrecipes/" + id).with(csrf).with(asOwner).param("name", "우리집테스트볶음2").param("ingredients", "양파 50g"))
				.andExpect(status().is3xxRedirection());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recipe_ingredients WHERE recipe_id = ?", Integer.class, id)).isEqualTo(1);
		mvc.perform(post.apply("/myrecipes/" + id + "/delete").with(csrf).with(asOther));
		mvc.perform(get("/recipes/" + id).with(asOwner)).andExpect(status().isOk());
		mvc.perform(post.apply("/myrecipes/" + id + "/delete").with(csrf).with(asOwner));
		mvc.perform(get("/recipes/" + id).with(asOwner)).andExpect(status().isNotFound());
	}

	@Test
	void 상황과_목표에_맞는_식사_조합을_추천한다() throws Exception {
		mealItemLoader.load();
		for (var situation : MealItemLoader.Situation.values()) {
			for (var goal : new com.springboot.domain.NutritionGoal[]{null, com.springboot.domain.NutritionGoal.LOW_SODIUM,
					com.springboot.domain.NutritionGoal.LOW_CALORIE, com.springboot.domain.NutritionGoal.HIGH_PROTEIN}) {
				var plan = mealPlanService.plan(situation, goal, "F", 0);
				var t = plan.target();
				assertThat(plan.combos()).as(situation + " " + goal).isNotEmpty();
				assertThat(plan.combos()).allSatisfy(c -> {
					assertThat(c.totals().energy()).isBetween(t.energyMin(), t.energyMax());
					assertThat(c.totals().sodium()).isLessThanOrEqualTo(t.sodiumMax());
					assertThat(c.totals().protein()).isGreaterThanOrEqualTo(t.proteinMin());
				});
			}
		}
		// 직접 조리는 레시피 상세로 연결, 화면은 비회원도 본다
		var home = mealPlanService.plan(MealItemLoader.Situation.HOME, null, null, 0);
		assertThat(home.recipeIds()).isNotEmpty();
		mvc.perform(get("/meals").param("situation", "EATING_OUT").param("goal", "LOW_SODIUM"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("맞춤형 식사 조합 추천")))
				.andExpect(content().string(containsString("쌀밥 1공기")))
				.andExpect(content().string(containsString("600 mg 이하")));
		mvc.perform(get("/meals").param("situation", "HOME"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("href=\"/recipes/")));
	}
}
