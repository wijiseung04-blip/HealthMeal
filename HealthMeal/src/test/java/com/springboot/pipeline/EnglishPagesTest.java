package com.springboot.pipeline;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
/** 8장 다국어: 영어 화면에 한국어 화면 문구(메시지 리소스·문장 틀의 한국어)가 남지 않는지 모든 주요 화면을 그려 확인한다 */
class EnglishPagesTest {
	@Autowired FoodLoader foodLoader;
	@Autowired RecipeLoader recipeLoader;
	@Autowired MealItemLoader mealItemLoader;
	@Autowired MockMvc mvc;
	@Autowired com.springboot.service.MemberService memberService;
	@Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
	@Autowired com.springboot.service.SubstitutionService substitutionService;

	@BeforeAll
	void load() throws Exception {
		foodLoader.load();
		recipeLoader.load();
		mealItemLoader.load();
	}

	static final Pattern TEXT = Pattern.compile(">([^<>]*[가-힣][^<>]*)<");
	static final Pattern ATTR = Pattern.compile("(?:title|placeholder|aria-label|value|alt|content|data-[a-z-]+)=\"([^\"]*[가-힣][^\"]*)\"");

	@Test
	void 영어_화면에는_한국어_화면_문구가_남지_않는다() throws Exception {
		String lang = "en";
		var f = new com.springboot.dto.SignupForm();
		f.setUsername("scanuser1"); f.setName("스캔"); f.setPassword("password123"); f.setPasswordConfirm("password123");
		memberService.signup(f);
		var u = user("scanuser1");
		MockHttpSession s = new MockHttpSession();
		mvc.perform(get("/").param("lang", lang).session(s).with(u));
		mvc.perform(post("/mypage/profile").with(csrf()).with(u).session(s).param("birthYear", "1990").param("sex", "M").param("heightCm", "175")
				.param("weightKg", "80").param("activity", "LOW_ACTIVE").param("excludedFoods", "새우"));
		String loc = mvc.perform(post("/mypage/health").with(csrf()).with(u).session(s).param("sex", "M")
				.param("values[SYSTOLIC_BP]", "138").param("values[FASTING_GLUCOSE]", "112").param("values[LDL]", "145").param("values[BMI]", "26.7")
				.param("values[AST]", "55")).andReturn().getResponse().getRedirectedUrl();
		Long recipe = jdbc.queryForObject("SELECT id FROM recipes WHERE seq = '720'", Long.class);
		mvc.perform(post("/recipes/" + recipe + "/favorite").with(csrf()).with(u).session(s));
		String my = mvc.perform(post("/myrecipes").with(csrf()).with(u).session(s).param("name", "스캔볶음").param("category", "반찬")
				.param("ingredients", "양파 100g\n돼지고기 등심 150g\n간장 15g\n버터 10g").param("steps", "팬에 버터를 두르고 볶는다.")).andReturn().getResponse().getRedirectedUrl();
		var sug = substitutionService.suggest(recipe, com.springboot.domain.NutritionGoal.LOW_SODIUM).orElseThrow();
		String opt = sug.options().isEmpty() ? "" : sug.options().getFirst().id();
		mvc.perform(post("/recipes/" + recipe + "/adjust/save").with(csrf()).with(u).session(s).param("goal", "LOW_SODIUM").param("option", opt));
		var meal = mvc.perform(get("/meals").param("situation", "HOME").with(u).session(s)).andReturn().getResponse().getContentAsString();
		Matcher mi = Pattern.compile("name=\"items\" value=\"(\\d+)\"").matcher(meal);
		List<String> items = new ArrayList<>();
		while (mi.find() && items.size() < 3) items.add(mi.group(1));
		var save = post("/meals/save").with(csrf()).with(u).session(s).param("situation", "HOME").param("goal", "LOW_SODIUM").param("page", "0");
		for (String it : items) save = save.param("items", it);
		mvc.perform(save);

		List<String> pages = List.of("/", "/recipes", "/recipes?q=된장", "/recipes/" + recipe, "/recipes/" + recipe + "?goal=LOW_SODIUM",
				"/recipes/" + recipe + "/adjust?goal=LOW_SODIUM", "/recipes/" + recipe + "/adjust?goal=LOW_SODIUM&option=" + opt,
				"/recipes/" + recipe + "/adjust?goal=LOW_CALORIE", "/recipes/" + recipe + "/adjust?goal=HIGH_FIBER",
				"/meals", "/meals?situation=CONVENIENCE&goal=LOW_SUGAR", "/meals?situation=EATING_OUT&goal=", "/meals?situation=HOME&goal=HIGH_FIBER",
				"/mypage", "/mypage/profile", "/mypage/goals", "/mypage/health/new", loc, "/myrecipes/new", my, my + "/../" ,
				"/recipes/999999", "/login", "/signup", "/game");
		// 한국어 문구 목록: messages_ko 값과 sentences_en의 한국어 고정 부분 (공공 데이터 이름과 겹치는 짧은 낱말은 뺀다)
		Set<String> korean = new LinkedHashSet<>();
		var props = new java.util.Properties();
		try (var in = new java.io.InputStreamReader(getClass().getResourceAsStream("/messages/messages_ko.properties"), java.nio.charset.StandardCharsets.UTF_8)) {
			props.load(in);
		}
		for (String v : props.stringPropertyNames().stream().map(props::getProperty).toList()) {
			for (String part : v.replace("''", "'").split("\\{\\d}")) if (part.strip().length() >= 6 && part.matches(".*[가-힣].*")) korean.add(part.strip());
		}
		String rules = new String(getClass().getResourceAsStream("/messages/sentences_en.txt").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		for (String line : rules.split("\\R")) {
			if (line.isBlank() || line.startsWith("#") || !line.contains("\t")) continue;
			for (String part : line.split("\t")[0].split("\\{\\d}")) if (part.strip().length() >= 6 && part.matches(".*[가-힣].*")) korean.add(part.strip());
		}
		List<String> leftovers = new ArrayList<>();
		for (String p : pages) {
			if (p == null) continue;
			var r = mvc.perform(get(p.replace("/../", "")).with(u).session(s)).andReturn().getResponse();
			String html = r.getContentAsString().replaceAll("(?s)<script.*?</script>", "").replaceAll("&amp;", "&");
			for (String k : korean) if (html.contains(k)) leftovers.add(p + " → " + k);
			// 회원이 누르는 POST 폼에는 CSRF 토큰이 있어야 한다 (th:action이 아니면 빠져 403)
			java.util.regex.Matcher forms = java.util.regex.Pattern.compile("(?s)<form[^>]*method=\"post\".*?</form>").matcher(html);
			while (forms.find()) if (!forms.group().contains("name=\"_csrf\"")) leftovers.add(p + " → CSRF 토큰 없는 폼: " + forms.group().substring(0, Math.min(120, forms.group().length())));
		}
		// 영어 게임: 문제 재료는 번역이 있는 이름만, 화면 문구(JSON)도 영어
		String deck = mvc.perform(get("/api/game/foods").param("nutrient", "SODIUM").session(s)).andReturn().getResponse().getContentAsString();
		org.assertj.core.api.Assertions.assertThat(deck).contains("\"foods\":[{").doesNotContainPattern("[가-힣]");
		String game = mvc.perform(get("/game").session(s)).andReturn().getResponse().getContentAsString();
		Matcher text = Pattern.compile("(?s)<script id=\"game-text\"[^>]*>(.*?)</script>").matcher(game);
		org.assertj.core.api.Assertions.assertThat(text.find()).isTrue();
		org.assertj.core.api.Assertions.assertThat(text.group(1)).contains("Which has more {0}?").doesNotContainPattern("[가-힣]");
		org.assertj.core.api.Assertions.assertThat(leftovers).isEmpty();
		org.assertj.core.api.Assertions.assertThat(korean).hasSizeGreaterThan(300);
	}
}
