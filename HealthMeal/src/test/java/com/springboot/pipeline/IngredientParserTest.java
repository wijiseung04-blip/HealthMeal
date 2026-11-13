package com.springboot.pipeline;

import com.springboot.pipeline.ParsedIngredient.Status;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** 실제 COOKRCP01 원문에서 가져온 사례로 규칙별 동작을 확인한다. */
class IngredientParserTest {

	private static List<ParsedIngredient> parse(String text) {
		return IngredientParser.parse(text);
	}

	@Test
	void 그램_표기와_괄호_안_개수를_구분한다() {
		assertThat(parse("연두부 75g(3/4모), 칵테일새우 20g(5마리)"))
				.extracting(ParsedIngredient::name, ParsedIngredient::grams, ParsedIngredient::note, ParsedIngredient::status)
				.containsExactly(tuple("연두부", 75.0, "3/4모", Status.GRAM), tuple("칵테일새우", 20.0, "5마리", Status.GRAM));
	}

	@Test
	void 괄호_안_쉼표에서는_나누지_않는다() {
		assertThat(parse("닭고기(가슴살, 30g), 쌀(50g)"))
				.extracting(ParsedIngredient::name, ParsedIngredient::grams, ParsedIngredient::note)
				.containsExactly(tuple("닭고기", 30.0, "가슴살"), tuple("쌀", 50.0, null));
	}

	@Test
	void 단독_줄과_콜론은_소제목이다() {
		List<ParsedIngredient> r = parse("버섯볶음\n새송이버섯 100g(3개)\n두유 소스\n두유 20g(1⅓큰술)\n- 소스 : 요거트(20g)");
		assertThat(r).extracting(ParsedIngredient::section, ParsedIngredient::name)
				.containsExactly(tuple("버섯볶음", "새송이버섯"), tuple("두유 소스", "두유"), tuple("소스", "요거트"));
	}

	@Test
	void 재료와_소제목이_콜론_앞에_붙어_있으면_나눈다() {
		List<ParsedIngredient> r = parse("주재료: 시금치(포항초) 30, 비트 10 드레싱: 발사믹식초 5");
		assertThat(r).extracting(ParsedIngredient::section, ParsedIngredient::name, ParsedIngredient::grams, ParsedIngredient::status)
				.containsExactly(
						tuple("주재료", "시금치", 30.0, Status.ASSUMED_GRAM),
						tuple("주재료", "비트", 10.0, Status.ASSUMED_GRAM),
						tuple("드레싱", "발사믹식초", 5.0, Status.ASSUMED_GRAM));
	}

	@Test
	void 큰술_작은술_표기를_정규화한다() {
		assertThat(parse("식초(1.5Ts), 참깨(0.3ts), 두유 1⅓큰술"))
				.extracting(ParsedIngredient::name, ParsedIngredient::amount, ParsedIngredient::unit, ParsedIngredient::status)
				.containsExactly(
						tuple("식초", 1.5, "큰술", Status.NEEDS_CONVERSION),
						tuple("참깨", 0.3, "작은술", Status.NEEDS_CONVERSION),
						tuple("두유", 1 + 1 / 3.0, "큰술", Status.NEEDS_CONVERSION));
	}

	@Test
	void 분수와_범위를_숫자로_바꾼다() {
		assertThat(parse("사과(1/4개), 방울토마토(6~7개)"))
				.extracting(ParsedIngredient::amount)
				.containsExactly(0.25, 6.5);
	}

	@Test
	void 밀리리터와_리터는_ML로_둔다() {
		assertThat(parse("물 300ml, 우유 1L"))
				.extracting(ParsedIngredient::amount, ParsedIngredient::unit, ParsedIngredient::grams, ParsedIngredient::status)
				.containsExactly(tuple(300.0, "ml", null, Status.ML), tuple(1000.0, "ml", null, Status.ML));
	}

	@Test
	void 약간_적당량은_수량을_만들지_않는다() {
		assertThat(parse("후춧가루(약간), 소금적당량"))
				.extracting(ParsedIngredient::name, ParsedIngredient::amount, ParsedIngredient::status)
				.containsExactly(tuple("후춧가루", null, Status.VAGUE), tuple("소금", null, Status.VAGUE));
	}

	@Test
	void 쉼표가_빠진_재료를_나눈다() {
		assertThat(parse("떡볶이떡 40g 고추장 8g, 로즈마리 1g. 마늘기름 15g, 흰후춧가루 0.1g레몬즙 5g"))
				.extracting(ParsedIngredient::name)
				.containsExactly("떡볶이떡", "고추장", "로즈마리", "마늘기름", "흰후춧가루", "레몬즙");
	}

	@Test
	void 씩이_붙은_그램은_나누지_않는다() {
		assertThat(parse("파프리카 2g씩")).extracting(ParsedIngredient::name, ParsedIngredient::grams)
				.containsExactly(tuple("파프리카", 2.0));
	}

	@Test
	void 소스소개_태그는_닫히지_않아도_줄을_나눈다() {
		List<ParsedIngredient> r = parse("오이 10g [소스소개 단촛물:물60g, 식초 1.5g");
		assertThat(r).extracting(ParsedIngredient::section, ParsedIngredient::name, ParsedIngredient::grams)
				.containsExactly(tuple(null, "오이", 10.0), tuple("단촛물", "물", 60.0), tuple("단촛물", "식초", 1.5));
	}

	@Test
	void 괄호_짝이_안_맞으면_쉼표로만_나눈다() {
		assertThat(parse("바지락(모시조개( 200g(30개), 양파 15g(2*4cm), 후추 약간"))
				.extracting(ParsedIngredient::name)
				.containsExactly("바지락", "양파", "후추");
	}

	@Test
	void 이름_없는_수량은_앞_재료에_붙인다() {
		assertThat(parse("생강즙 1.25g, 포도씨유, 12g, 생크림 30g"))
				.extracting(ParsedIngredient::name, ParsedIngredient::grams)
				.containsExactly(tuple("생강즙", 1.25), tuple("포도씨유", 12.0), tuple("생크림", 30.0));
	}

	@Test
	void 중첩_괄호와_등호_표기에서_그램을_고른다() {
		assertThat(parse("갈치(70g(1토막)), 감자(2개=360g)"))
				.extracting(ParsedIngredient::name, ParsedIngredient::grams)
				.containsExactly(tuple("갈치", 70.0), tuple("감자", 360.0));
	}

	@Test
	void 인분_기준과_br_태그는_재료가_아니다() {
		assertThat(parse("2인분 기준<br>두부 100g")).extracting(ParsedIngredient::name).containsExactly("두부");
	}

	@Test
	void 줄_앞의_재료_소제목을_떼어낸다() {
		assertThat(parse("재료 닭가슴살(150g), 당근(10g)")).extracting(ParsedIngredient::name)
				.containsExactly("닭가슴살", "당근");
	}

	@Test
	void 줄_앞에_붙은_소제목을_재료명에서_떼어낸다() {
		List<ParsedIngredient> r = parse("육수 다시마(2.5g), 멸치(2.5g)\n톳 무침양념 설탕(2g)\n[주재료]현미(60g)\n(반죽재료) 강력분 290g");
		assertThat(r).extracting(ParsedIngredient::section, ParsedIngredient::name)
				.containsExactly(
						tuple("육수", "다시마"), tuple("육수", "멸치"),
						tuple("톳 무침양념", "설탕"),
						tuple("주재료", "현미"),
						tuple("반죽재료", "강력분"));
	}

	@Test
	void 마지막_단어는_소제목으로_떼지_않는다() {
		assertThat(parse("토마토 소스 30g")).extracting(ParsedIngredient::section, ParsedIngredient::name)
				.containsExactly(tuple(null, "토마토 소스"));
	}

	@Test
	void 원문에_없는_수량은_만들지_않는다() {
		assertThat(parse("물녹말(녹말가루 10g, 물 10g), 도토리묵"))
				.extracting(ParsedIngredient::amount, ParsedIngredient::status)
				.containsOnly(tuple(null, Status.NO_AMOUNT));
	}
}
