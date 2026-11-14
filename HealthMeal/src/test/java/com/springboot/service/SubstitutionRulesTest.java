package com.springboot.service;

import com.springboot.domain.NutritionGoal;
import com.springboot.nutrition.NutritionEngine.Comparison;
import com.springboot.nutrition.NutritionEngine.RateKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class SubstitutionRulesTest {

	private static final Pattern GRAIN = Pattern.compile("현미|분도미|잡곡");

	@Test
	void 같은_식품의_다른_부위는_한_구간만_다르고_조리_상태가_같아야_한다() {
		// 고기: 부위·등급
		assertThat(SubstitutionService.isVariant("돼지고기, 삼겹살, 생것", "돼지고기, 안심, 생것", null)).isTrue();
		assertThat(SubstitutionService.isVariant("소고기, 한우(1++등급), 등심, 생것", "소고기, 한우(3등급), 등심, 생것", null)).isTrue();
		assertThat(SubstitutionService.isVariant("돼지고기, 삼겹살, 생것", "돼지고기, 안심, 구운것", null)).isFalse();   // 조리 상태
		assertThat(SubstitutionService.isVariant("돼지고기, 삼겹살, 생것", "돼지고기, 간, 생것", null)).isFalse();      // 내장
		assertThat(SubstitutionService.isVariant("오리고기, 훈제", "오리고기, 삶은것, 껍질 포함", null)).isFalse();    // 구간 수·상태
		assertThat(SubstitutionService.isVariant("소고기, 한우(1등급), 등심, 생것", "소고기, 수입산, 안심, 생것", null)).isFalse(); // 두 구간
		// 곡류: 더 거친 곡물로만
		assertThat(SubstitutionService.isVariant("멥쌀, 백미, 생것", "멥쌀, 현미, 생것", GRAIN)).isTrue();
		assertThat(SubstitutionService.isVariant("멥쌀떡, 가래떡", "멥쌀떡, 백설기", GRAIN)).isFalse();
		assertThat(SubstitutionService.isVariant("귀리, 쌀귀리, 도정, 밥", "귀리, 오트밀", GRAIN)).isFalse();
	}

	@Test
	void 다른_영양소가_10퍼센트_넘게_나빠지면_경고한다() {
		List<Comparison> c = List.of(
				new Comparison("나트륨", 1000.0, 700.0, -300.0, -0.3, RateKind.EXACT, true),  // 목표 영양소
				new Comparison("당류", 10.0, 12.0, 2.0, 0.2, RateKind.EXACT, true),            // 늘면 나쁨
				new Comparison("단백질", 20.0, 17.0, -3.0, -0.15, RateKind.EXACT, true),       // 줄면 나쁨
				new Comparison("지방", 10.0, 10.5, 0.5, 0.05, RateKind.EXACT, true));          // 10% 미만
		assertThat(SubstitutionService.sideEffects(c, NutritionGoal.LOW_SODIUM))
				.extracting(SubstitutionService.SideEffect::nutrient).containsExactly("당류", "단백질");
	}

	@Test
	void 감량_폭이_클수록_맛_영향이_크다() {
		assertThat(List.of(0.8, 0.7, 0.5)).map(SubstitutionService::reduceImpact)
				.containsExactly(SubstitutionService.Impact.LOW, SubstitutionService.Impact.MEDIUM, SubstitutionService.Impact.HIGH);
	}
}
