package com.springboot.nutrition;

import com.springboot.nutrition.IngredientAnalysis.CulinaryRole;
import com.springboot.nutrition.IngredientAnalysis.NutritionRole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 재료 역할 2축 분석: 조리 역할(식품군·조리 순서), 영양 역할(100g당 성분), 근거 문장 */
class IngredientAnalysisTest {

	private static IngredientAnalysis.Result a(String name, String code, String group, Map<String, Double> v, String... steps) {
		return IngredientAnalysis.analyze(name, code, group, 10.0, v, List.of(steps));
	}

	@Test
	void 조리_역할은_식품군과_조리_순서로_정한다() {
		assertThat(a("물", "WATER", null, Map.of()).culinary()).isEqualTo(CulinaryRole.MOISTURE);
		assertThat(a("멸치육수", null, null, Map.of()).culinary()).isEqualTo(CulinaryRole.MOISTURE);
		assertThat(a("마요네즈", "R0150020009a", "조미료류", Map.of()).culinary()).isEqualTo(CulinaryRole.EMULSIFY);
		assertThat(a("버터", "N0080000009a", "유지류", Map.of(), "팬에 버터를 두르고 양파를 볶는다.").culinary()).isEqualTo(CulinaryRole.COOKING_MEDIUM);
		assertThat(a("버터", "N0080000009a", "유지류", Map.of(), "버터와 설탕을 섞어 반죽한다.").culinary()).isEqualTo(CulinaryRole.STRUCTURE);
		assertThat(a("밀가루", "A0160080005a", "곡류 및 그 제품", Map.of(), "새우에 밀가루를 묻혀 튀긴다.").culinary()).isEqualTo(CulinaryRole.BIND);
		assertThat(a("감자전분", "X", "곡류 및 그 제품", Map.of(), "전분물을 넣어 걸쭉하게 한다.").culinary()).isEqualTo(CulinaryRole.THICKEN);
		assertThat(a("간장", "R0010010009a", "조미료류", Map.of()).culinary()).isEqualTo(CulinaryRole.SEASONING);
		assertThat(a("후춧가루", "R0000000000a", "조미료류", Map.of()).culinary()).isEqualTo(CulinaryRole.FLAVOR);
		assertThat(a("생크림", "M0130070009a", "우유류 및 유제품", Map.of()).culinary()).isEqualTo(CulinaryRole.EMULSIFY);
		assertThat(a("돼지고기", "I0000000000a", "육류", Map.of()).culinary()).isEqualTo(CulinaryRole.MAIN);
	}

	@Test
	void 영양_역할은_100g당_성분으로_정한다() {
		assertThat(a("간장", "c", "조미료류", Map.of("나트륨", 5000.0)).nutrition()).isEqualTo(NutritionRole.SODIUM);
		assertThat(a("식용유", "c", "유지류", Map.of("지방", 100.0)).nutrition()).isEqualTo(NutritionRole.FAT);
		assertThat(a("닭가슴살", "c", "육류", Map.of("단백질", 23.0, "지방", 1.0)).nutrition()).isEqualTo(NutritionRole.PROTEIN);
		assertThat(a("밥", "c", "곡류", Map.of("단백질", 2.5, "탄수화물", 33.0)).nutrition()).isEqualTo(NutritionRole.CARBOHYDRATE);
		assertThat(a("양파", "c", "채소류", Map.of("탄수화물", 8.0)).nutrition()).isEqualTo(NutritionRole.FIBER);
		assertThat(a("물", "WATER", null, Map.of()).nutrition()).isEqualTo(NutritionRole.OTHER);
	}

	@Test
	void 근거는_재료가_나온_조리_문장이다() {
		assertThat(a("버터", "c", "유지류", Map.of(), "양파를 썬다.", "팬에 버터를 두르고 볶는다.").evidence()).contains("버터를 두르고");
		assertThat(a("소금", "c", "조미료류", Map.of(), "양파를 썬다.").evidence()).isNull();
	}
}
