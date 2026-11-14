package com.springboot.pipeline;

import com.springboot.pipeline.FoodMatcher.Grade;
import com.springboot.pipeline.FoodRecord.ValueType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class FoodSourcesTest {

	private static final String HEADER = "식품코드,식품명,대표식품명,식품중분류명,식품소분류명,식품세분류명,에너지(kcal),나트륨(mg),데이터생성방법명,데이터생성일자,생산·채취·포획월,식품대분류명,출처명";

	@Test
	void 수산물만_대표값_하나로_읽고_빈_값은_미측정이다(@TempDir Path dir) throws Exception {
		Path csv = dir.resolve("raw.csv");
		Files.writeString(csv, String.join("\r\n", HEADER,
				"A1,양파_생것,양파,해당없음,해당없음,생것,34,1,분석,2020-01-01,,채소류,농촌진흥청(국가표준식품성분표)",
				"R1-1,다시마류_참다시마_전체_말린것_해남_7월,다시마류,참다시마,전체,말린것,120,2000,분석,2009-11-30,07,해조류,국립수산과학원(표준수산물성분표)",
				"R1-2,다시마류_참다시마_전체_말린것_대표_평균,다시마류,참다시마,전체,말린것,110,,분석,2023-11-30,,해조류,국립수산과학원(표준수산물성분표)",
				"R2-1,홍합류_참담치(홍합)_육_생것_수입_평균,홍합류,참담치(홍합),육,생것,80,300,수집,2022-11-30,,어패류 및 기타 수산물,식품의약품안전처",
				"R3-1,\"송어류_송어_양식,전체_튀긴것(튀김옷, 수분많은 반죽)_수입_평균\",송어류,송어,\"양식,전체\",\"튀긴것(튀김옷, 수분많은 반죽)\",200,100,수집,2022-11-30,,어패류 및 기타 수산물,식품의약품안전처"),
				Charset.forName("MS949"));

		List<FoodRecord> foods = FoodSources.supplement(csv);

		assertThat(foods).extracting(FoodRecord::code, FoodRecord::name).containsExactly(
				tuple("R1-2", "참다시마(다시마), 말린것"), // 농진청 행 제외, 지역·월별보다 대표·평균, 대표명을 별칭으로
				tuple("R2-1", "참담치(홍합), 육, 생것"),   // 이미 별칭이 있으면 그대로
				tuple("R3-1", "송어, 양식, 튀긴것(튀김옷·수분많은 반죽)"));
		assertThat(foods.getFirst().nutrients()).extracting(FoodRecord.Nutrient::name, FoodRecord.Nutrient::value, FoodRecord.Nutrient::type)
				.contains(tuple("에너지", 110.0, ValueType.MEASURED), tuple("나트륨", null, ValueType.NOT_MEASURED));
		assertThat(foods.get(1).nutrients().getFirst().type()).isEqualTo(ValueType.ESTIMATED); // 수집값
	}

	private static FoodRecord food(String code, String name) {
		return new FoodRecord(code, name, null, null, null, List.of());
	}

	@Test
	void 보충_식품은_국가표준에서_못_찾을_때만_쓴다() {
		FoodMatcher matcher = new FoodMatcher(List.of(
				food("K1", "새우, 흰다리새우, 냉동"), food("K2", "전복, 튀긴것"),
				food("R-1", "줄새우(새우), 생것"), food("R-2", "전복, 생것")), Map.of());

		assertThat(List.of("칵테일새우", "전복")).map(matcher::match)
				.extracting(FoodMatcher.Match::foodCode).containsExactly("K1", "R-2");
	}

	@Test
	void 말린_재료와_가루는_생것으로_대신하지_않는다() {
		FoodMatcher matcher = new FoodMatcher(List.of(
				food("P1", "포도, 생것"), food("P2", "포도, 건포도"),
				food("C1", "고추, 청양고추, 생것"),
				food("S1", "새우, 튀긴것(빵가루)"), food("S2", "새우, 대하, 말린것"),
				food("B1", "바질, 잎, 생것"),
				food("W1", "밀, 강력밀가루")), Map.of());

		assertThat(List.of("건포도", "건청양고추", "새우가루", "바질가루", "밀가루")).map(matcher::match)
				.extracting(FoodMatcher.Match::grade, FoodMatcher.Match::foodCode).containsExactly(
						tuple(Grade.SIMILAR, "P2"),   // "건포도"는 그 자체가 식품명
						tuple(Grade.UNMATCHED, null), // 말린 청양고추가 없다
						tuple(Grade.SIMILAR, "S2"),   // 빵가루 튀김이 아니라 말린 새우
						tuple(Grade.UNMATCHED, null), // 생 바질잎으로 대신하지 않는다
						tuple(Grade.SIMILAR, "W1"));
	}
}
