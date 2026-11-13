package com.springboot.nutrition;

import com.springboot.nutrition.NutritionEngine.CoverageLevel;
import com.springboot.nutrition.NutritionEngine.Item;
import com.springboot.nutrition.NutritionEngine.RateKind;
import com.springboot.nutrition.NutritionEngine.Result;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class NutritionEngineTest {

	private static final List<String> N = List.of("에너지", "나트륨");

	// 100g당 값
	private static final Map<String, Map<String, Double>> DB = new HashMap<>();

	static {
		DB.put("ONION", Map.of("에너지", 34.0, "나트륨", 2.0));
		DB.put("SOY", Map.of("에너지", 60.0, "나트륨", 5500.0));
		DB.put("LOW_SOY", Map.of("에너지", 60.0, "나트륨", 3300.0));
		Map<String, Double> noSodium = new HashMap<>();
		noSodium.put("에너지", 900.0);
		noSodium.put("나트륨", null); // 미측정
		DB.put("OIL", noSodium);
	}

	private static Result calc(Item... items) {
		return NutritionEngine.calculate(List.of(items), N, DB::get);
	}

	@Test
	void 사용량과_100g당_값으로_합계를_낸다() {
		Result r = calc(new Item("양파", "ONION", 50.0), new Item("간장", "SOY", 10.0));

		// 양파 34×50/100=17, 간장 60×10/100=6 / 나트륨 2×0.5=1, 5500×0.1=550
		assertThat(r.totals().get("에너지")).isCloseTo(23.0, within(1e-9));
		assertThat(r.totals().get("나트륨")).isCloseTo(551.0, within(1e-9));
		assertThat(r.coverage("나트륨")).isEqualTo(CoverageLevel.COMPLETE);
		assertThat(r.coverage().weightCoverage()).isEqualTo(1.0);
		assertThat(r.unknownItems()).isEmpty();
	}

	@Test
	void 미측정_값은_0으로_메우지_않고_부분_커버리지로_표시한다() {
		Result r = calc(new Item("양파", "ONION", 100.0), new Item("식용유", "OIL", 10.0));

		assertThat(r.totals().get("나트륨")).isEqualTo(2.0);
		assertThat(r.coverage("나트륨")).isEqualTo(CoverageLevel.PARTIAL);
		assertThat(r.coverage("에너지")).isEqualTo(CoverageLevel.COMPLETE);
		assertThat(r.items().get(1).missingNutrients()).containsExactly("나트륨");
	}

	@Test
	void 사용량이나_식품을_모르는_재료는_미확인으로_분리한다() {
		Result r = calc(new Item("양파", "ONION", 100.0), new Item("소금 약간", "SOY", null), new Item("외계과일", null, 30.0));

		assertThat(r.unknownItems()).containsExactly("소금 약간", "외계과일");
		assertThat(r.coverage().totalItems()).isEqualTo(3);
		assertThat(r.coverage().matchedItems()).isEqualTo(2);
		assertThat(r.coverage().knownAmountItems()).isEqualTo(2);
		assertThat(r.coverage().computedItems()).isEqualTo(1);
		assertThat(r.coverage().weightCoverage()).isNull(); // 전체 중량을 모르면 산출하지 않는다
		assertThat(r.coverage("나트륨")).isEqualTo(CoverageLevel.PARTIAL);
	}

	@Test
	void 모든_재료_확인시_정확한_변화율을_낸다() {
		Result before = calc(new Item("양파", "ONION", 50.0), new Item("간장", "SOY", 10.0));
		Result after = calc(new Item("양파", "ONION", 50.0), new Item("저염간장", "LOW_SOY", 10.0));

		var c = NutritionEngine.compare("나트륨", before, after, 330.0 - 550.0);
		assertThat(c.delta()).isEqualTo(-220.0);
		assertThat(c.rate()).isCloseTo(-220.0 / 551.0, within(1e-9));
		assertThat(c.rateKind()).isEqualTo(RateKind.EXACT);
	}

	@Test
	void 미확인_재료가_있으면_변화율은_상한값이다() {
		Result before = calc(new Item("간장", "SOY", 10.0), new Item("소금 약간", null, null));
		Result after = calc(new Item("저염간장", "LOW_SOY", 10.0), new Item("소금 약간", null, null));

		var c = NutritionEngine.compare("나트륨", before, after, -220.0);
		assertThat(c.rate()).isCloseTo(-0.4, within(1e-9)); // 실제 |변화율| ≤ 40%
		assertThat(c.rateKind()).isEqualTo(RateKind.UPPER_BOUND);
	}

	@Test
	void 대체_대상이_미확인이면_계산하지_않고_대체전_합계가_0이면_변화량만_낸다() {
		Result r = calc(new Item("양파", "ONION", 50.0));
		assertThat(NutritionEngine.compare("나트륨", r, r, null).computable()).isFalse();

		Result zero = calc(new Item("기름", "OIL", 10.0)); // 나트륨 미측정 → 대체 전 합계 0
		var c = NutritionEngine.compare("나트륨", zero, zero, 0.0);
		assertThat(c.computable()).isTrue();
		assertThat(c.rate()).isNull();
		assertThat(c.rateKind()).isEqualTo(RateKind.NOT_AVAILABLE);
	}
}
