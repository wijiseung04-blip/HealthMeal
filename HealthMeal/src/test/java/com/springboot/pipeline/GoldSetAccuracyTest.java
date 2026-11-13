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
		// 알려진 오류 1건: 목록에 없는 소제목 "볶음밥"이 재료명에 붙는다
		assertThat(r.mismatches()).extracting(GoldSetAccuracy.Mismatch::raw, GoldSetAccuracy.Mismatch::field)
				.containsExactly(org.assertj.core.api.Assertions.tuple("볶음밥 쌀(100g)", "name"));
	}
}
