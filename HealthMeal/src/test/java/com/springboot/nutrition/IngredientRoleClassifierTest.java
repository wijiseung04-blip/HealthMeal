package com.springboot.nutrition;

import com.springboot.nutrition.IngredientRoleClassifier.Role;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IngredientRoleClassifierTest {

	private static Role oil(String name, String... steps) {
		return IngredientRoleClassifier.classify(name, "유지류", List.of(steps)).role();
	}

	private static Role sugar(String name, String... steps) {
		return IngredientRoleClassifier.classify(name, "당류", List.of(steps)).role();
	}

	@Test
	void 기름은_조리_순서로_볶음_튀김_양념을_구분한다() {
		// 재료 바로 뒤 "두르고"가 먼저: 같은 문장의 반죽은 부침 재료
		assertThat(oil("식용유", "1. 팬에 기름을 두르고 반죽을 부어 굽는다.")).isEqualTo(Role.COOKING_MEDIUM);
		assertThat(oil("참기름", "3. 간장, 설탕, 참기름을 섞어 양념장을 만든다.")).isEqualTo(Role.FLAVOR);
		assertThat(oil("참기름", "2. 볶은 호박이 식으면 참기름에 무친다.")).isEqualTo(Role.FLAVOR);
		assertThat(oil("튀김기름", "4. 170℃ 기름에 넣는다.")).isEqualTo(Role.DEEP_FRY);
		assertThat(oil("식용유", "6. 팬에 기름을 넉넉히 두르고 튀겨준다.")).isEqualTo(Role.DEEP_FRY);
		assertThat(oil("버터", "2. 버터, 박력분, 소금을 넣어 반죽한다.")).isEqualTo(Role.STRUCTURE);
	}

	@Test
	void 설탕은_볶음_튀김_문장에_있어도_맛_내기이고_머랭은_구조다() {
		assertThat(sugar("설탕", "5. 튀긴 뒤 뜨거울 때 설탕을 뿌린다.")).isEqualTo(Role.FLAVOR);
		assertThat(sugar("설탕", "2. 달걀흰자에 설탕을 넣으며 단단한 머랭을 만든다.")).isEqualTo(Role.STRUCTURE);
		// 같은 절까지만 본다: 뒤의 "오븐에 구운 삼겹살"은 설탕과 상관없다
		assertThat(sugar("설탕", "6. 오렌지즙과 설탕을 넣고 졸인 후 소스를 만들고, 오븐에 구운 삼겹살에 뿌린다.")).isEqualTo(Role.FLAVOR);
	}

	@Test
	void 근거가_없으면_판단하지_않는다() {
		// "기름기"는 재료가 아니다
		assertThat(oil("식용유", "1. 돼지갈비는 기름기를 제거한다.", "2. 양파를 썬다.")).isEqualTo(Role.UNKNOWN);
		var c = IngredientRoleClassifier.classify("올리고당", "당류", List.of("1. 감자를 삶는다."));
		assertThat(c.role()).isEqualTo(Role.UNKNOWN);
		assertThat(c.evidence()).isNull();
	}

	@Test
	void 소수점에서는_문장을_나누지_않는다() {
		assertThat(IngredientRoleClassifier.sentences(List.of("1. 물 1.5컵에 설탕을 넣고 끓인다. 2. 식힌다.")))
				.containsExactly("1. 물 1.5컵에 설탕을 넣고 끓인다", " 2. 식힌다"); // 단계 번호 "1."도 나누지 않는다
	}
}
