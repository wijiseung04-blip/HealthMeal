package com.springboot.pipeline;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 파서를 고칠 때 사람이 확인한 정답셋 정확도가 떨어지지 않는지 확인한다 */
class GoldSetAccuracyTest {

	@Test
	void 정답셋과_비교해_알려진_오류_외에는_모두_일치한다() throws Exception {
		GoldSetAccuracy.Result r = GoldSetAccuracy.evaluate();

		assertThat(r.expectedItems()).isEqualTo(328);
		assertThat(r.parsedItems()).isEqualTo(r.expectedItems()); // 빠지거나 더 생긴 재료 없음
		// 알려진 불일치는 레시피 219뿐: 소제목 "볶음밥"이 재료명에 붙음, 정답셋 section "재료"는 원문에 없는 값(확인 중)
		assertThat(r.mismatches()).extracting(GoldSetAccuracy.Mismatch::recipeSeq).containsOnly("219");
		assertThat(r.mismatches()).filteredOn(m -> !m.field().equals("section"))
				.extracting(GoldSetAccuracy.Mismatch::raw).containsOnly("볶음밥 쌀(100g)");
	}
}
