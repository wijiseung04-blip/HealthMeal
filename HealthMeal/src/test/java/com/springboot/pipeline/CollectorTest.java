package com.springboot.pipeline;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollectorTest {

	@Test
	void 식품코드를_식품군별로_중복없이_뽑는다() {
		String xml = "<item><food_Code>A0170010009a</food_Code></item><item><food_Code>A0170010010</food_Code></item>"
				+ "<item><food_Code>A0170010009a</food_Code></item><item><food_Code>B0010010001a</food_Code></item>";

		assertThat(Collector.extractFoodCodes(xml, 'A')).containsExactly("A0170010009a", "A0170010010");
	}

	@Test
	void 다른_긴_숫자는_식품코드로_보지_않는다() {
		assertThat(Collector.extractFoodCodes("{\"x\":\"XA0170010009a\",\"y\":\"A01700100099\"}", 'A')).isEmpty();
	}

	@Test
	void 전체_건수를_읽는다() {
		assertThat(Collector.parseTotalCount("{\"COOKRCP01\":{\"total_count\":\"1156\",\"row\":[]}}")).isEqualTo(1156);
	}

	@Test
	void 전체_건수가_없으면_실패한다() {
		assertThatThrownBy(() -> Collector.parseTotalCount("{}")).isInstanceOf(IllegalStateException.class);
	}
}
