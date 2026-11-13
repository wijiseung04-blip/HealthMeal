package com.springboot.pipeline;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollectorTest {

	private static final String LIST_XML = """
			<response><header><result_Code>200</result_Code></header><body>
			<rcdcnt>3</rcdcnt><page_No>1</page_No><total_Count>440</total_Count>
			<items>
			<item><no>1</no><food_Code>A001001A010a</food_Code><food_Nm>귀리, 겉귀리, 도정, 생것</food_Nm><examin_Year>21</examin_Year></item>
			<item><no>2</no><food_Code>A0020000009a</food_Code><food_Nm>귀리, 오트밀</food_Nm></item>
			<item><no>3</no><food_Code>A001001A010a</food_Code><food_Nm>중복</food_Nm></item>
			</items></body></response>
			""";

	@Test
	void 목록_응답에서_식품코드를_중복없이_뽑는다() {
		assertThat(Collector.extractFoodCodes(LIST_XML)).containsExactly("A001001A010a", "A0020000009a");
	}

	@Test
	void 목록_응답의_전체_건수를_읽는다() {
		assertThat(Collector.parseFoodTotalCount(LIST_XML)).isEqualTo(440);
		assertThat(Collector.parseFoodTotalCount("<response/>")).isZero();
	}

	@Test
	void 전체_건수를_읽는다() {
		assertThat(Collector.parseTotalCount("{\"COOKRCP01\":{\"total_count\":\"1156\",\"row\":[]}}")).isEqualTo(1156);
	}

	@Test
	void 전체_건수가_없으면_실패한다() {
		assertThatThrownBy(() -> Collector.parseTotalCount("{}")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void 초당_제한만_재시도_대상이다() {
		assertThat(Collector.isPerSecondLimit("<errMsg>LIMITED_NUMBER_OF_SERVICE_REQUESTS_PER_SECOND_EXCEEDS_ERROR</errMsg>")).isTrue();
		assertThat(Collector.isPerSecondLimit("<errMsg>LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR</errMsg>")).isFalse();
	}
}
