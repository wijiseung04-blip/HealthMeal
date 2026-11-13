package com.springboot.pipeline;

import com.springboot.pipeline.FoodMatcher.Grade;
import com.springboot.pipeline.FoodRecord.ValueType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class FoodMatchingTest {

	private static final String DETAIL_XML = """
			<?xml version="1.0" encoding="UTF-8" standalone="yes" ?>
			<response><header><result_Code>200</result_Code></header><body><items><item>
			<food_Code>A001001A010a</food_Code><food_Grupp>곡류 및 그 제품</food_Grupp><food_Nm>귀리, 겉귀리, 도정, 생것</food_Nm>
			<origin_Nm>농진청</origin_Nm><examin_Year>21</examin_Year>
			<irdnt><irdnt_Se_Nm>일반성분</irdnt_Se_Nm>
			<irdnttcket><irdnt_Nm>에너지</irdnt_Nm><irdnt_Eng_Nm>Energy</irdnt_Eng_Nm><cont_Info>388.0</cont_Info><irdnt_Unit_Nm>㎉</irdnt_Unit_Nm></irdnttcket>
			<irdnttcket><irdnt_Nm>나트륨</irdnt_Nm><irdnt_Eng_Nm>Sodium</irdnt_Eng_Nm><cont_Info>(1,100)</cont_Info><irdnt_Unit_Nm>mg</irdnt_Unit_Nm></irdnttcket>
			<irdnttcket><irdnt_Nm>유당</irdnt_Nm><irdnt_Eng_Nm>Lactose</irdnt_Eng_Nm><cont_Info>Tr</cont_Info><irdnt_Unit_Nm>g</irdnt_Unit_Nm></irdnttcket>
			<irdnttcket><irdnt_Nm>비타민 D</irdnt_Nm><irdnt_Eng_Nm>Vitamin D</irdnt_Eng_Nm><cont_Info>-</cont_Info><irdnt_Unit_Nm>μg</irdnt_Unit_Nm></irdnttcket>
			</irdnt></item></items></body></response>
			""";

	@Test
	void 상세_응답에서_영양소와_값_종류를_읽는다() {
		FoodRecord f = FoodDetailParser.parse(DETAIL_XML);

		assertThat(f.code()).isEqualTo("A001001A010a");
		assertThat(f.baseName()).isEqualTo("귀리");
		assertThat(f.nutrients()).extracting(FoodRecord.Nutrient::name, FoodRecord.Nutrient::value, FoodRecord.Nutrient::type)
				.containsExactly(
						tuple("에너지", 388.0, ValueType.MEASURED),
						tuple("나트륨", 1100.0, ValueType.ESTIMATED),
						tuple("유당", 0.0, ValueType.TRACE),
						tuple("비타민 D", null, ValueType.NOT_MEASURED)); // 미측정은 0이 아니라 null
	}

	private static FoodRecord food(String code, String name) {
		return new FoodRecord(code, name, null, null, null, List.of());
	}

	private final FoodMatcher matcher = new FoodMatcher(List.of(
			food("F1", "양파, 생것"), food("F2", "양파, 데친것"),
			food("M1", "마늘, 생것"), food("M2", "마늘, 다진것"),
			food("P1", "파, 대파, 생것"),
			food("S1", "새우, 젓갈"), food("S2", "새우, 흰다리새우, 냉동"),
			food("G1", "고추, 풋고추, 생것"), food("G2", "고추, 빨간색, 생것"),
			food("O1", "참기름"),
			food("B1", "큰느타리버섯(새송이버섯), 생것")),
			Map.of("후춧가루", new String[]{"O1", "O"}));

	@Test
	void 등급과_선택_식품을_정한다() {
		assertThat(List.of("양파", "참기름", "다진 마늘", "대파", "새우", "홍고추", "새송이버섯", "물", "후춧가루", "외계과일"))
				.map(matcher::match)
				.extracting(FoodMatcher.Match::ingredient, FoodMatcher.Match::grade, FoodMatcher.Match::foodCode)
				.containsExactly(
						tuple("양파", Grade.EXACT, "F1"),          // 대표명 + 생것
						tuple("참기름", Grade.EXACT, "O1"),        // 식품명 그대로
						tuple("다진 마늘", Grade.SIMILAR, "M2"),   // 상태 접두어 → 다진것
						tuple("대파", Grade.SIMILAR, "P1"),        // 세부명
						tuple("새우", Grade.SIMILAR, "S2"),        // 젓갈보다 가공 안 된 것
						tuple("홍고추", Grade.SIMILAR, "G2"),      // 색 표기
						tuple("새송이버섯", Grade.EXACT, "B1"),    // 괄호 별칭도 대표명
						tuple("물", Grade.EXACT, FoodMatcher.WATER_CODE),
						tuple("후춧가루", Grade.MANUAL_CONFIRMED, "O1"),
						tuple("외계과일", Grade.UNMATCHED, null));
	}

	@Test
	void 병기된_중량으로_단위_환산값을_만든다() {
		List<ParsedIngredient> items = IngredientParser.parse("달걀 50g(1개), 달걀 60g(1개), 달걀 110g(2개), 오이 80g(1/2개), 달걀(2개), 물 200ml, 오이(1개)");
		UnitConversions c = UnitConversions.derive(items);

		assertThat(c.entries()).extracting(UnitConversions.Entry::name, UnitConversions.Entry::unit, UnitConversions.Entry::gramsPerUnit, UnitConversions.Entry::samples)
				.containsExactly(tuple("달걀", "개", 55.0, 3), tuple("오이", "개", 160.0, 1));
		assertThat(items.subList(4, 7)).map(c::toGrams).containsExactly(110.0, 200.0, 160.0);
	}

	@Test
	void 큰술과_작은술_표본을_3배로_합쳐_환산한다() {
		// 큰술 표본 1개(20g)는 이상값, 작은술 표본은 5g 근처
		List<ParsedIngredient> items = IngredientParser.parse("고춧가루 20g(1큰술), 고춧가루 5g(1작은술), 고춧가루 4g(1작은술), 고춧가루 6g(1작은술)");
		UnitConversions c = UnitConversions.derive(items);

		assertThat(c.entries()).extracting(UnitConversions.Entry::unit, UnitConversions.Entry::gramsPerUnit, UnitConversions.Entry::samples)
				// 큰술 [20, 12, 15, 18] → 16.5 / 작은술 [4, 5, 6, 6.67] → 5.5
				.containsExactlyInAnyOrder(tuple("큰술", 16.5, 4), tuple("작은술", 5.5, 4));
	}
}
