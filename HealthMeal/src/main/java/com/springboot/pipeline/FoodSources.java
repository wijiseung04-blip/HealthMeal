package com.springboot.pipeline;

import com.springboot.pipeline.FoodRecord.Nutrient;
import com.springboot.pipeline.FoodRecord.ValueType;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 식품 출처를 합친다.
 * 1. 국가표준식품성분 API (농촌진흥청) — data/raw/food-detail
 * 2. 전국통합식품영양성분정보(원재료성식품) 표준데이터 파일 — data/raw/food-integrated/raw-foods.csv (CP949)
 *    농진청 행은 1과 겹치므로 빼고, 수산물(국립수산과학원 표준수산물성분표·식약처)만 보충한다.
 */
final class FoodSources {

	static final String API_SOURCE = "국가표준식품성분정보";
	static final String SUPPLEMENT_SOURCE = "원재료성식품 표준데이터(수산물)";
	static final Path SUPPLEMENT = Collector.RAW.resolve("food-integrated").resolve("raw-foods.csv");

	// 파일 열 → API 영양소 이름 (같은 영양소를 같은 이름으로 저장해야 계산 엔진이 그대로 쓴다)
	private static final Map<String, String> COLUMNS = new LinkedHashMap<>();
	static {
		COLUMNS.put("에너지(kcal)", "에너지");
		COLUMNS.put("수분(g)", "수분");
		COLUMNS.put("단백질(g)", "단백질");
		COLUMNS.put("지방(g)", "지방");
		COLUMNS.put("회분(g)", "회분");
		COLUMNS.put("탄수화물(g)", "탄수화물");
		COLUMNS.put("당류(g)", "당류");
		COLUMNS.put("식이섬유(g)", "총 식이섬유");
		COLUMNS.put("칼슘(mg)", "칼슘");
		COLUMNS.put("철(mg)", "철");
		COLUMNS.put("인(mg)", "인");
		COLUMNS.put("칼륨(mg)", "칼륨");
		COLUMNS.put("나트륨(mg)", "나트륨");
		COLUMNS.put("비타민 A(μg RAE)", "비타민A");
		COLUMNS.put("레티놀(μg)", "레티놀");
		COLUMNS.put("베타카로틴(μg)", "베타카로틴");
		COLUMNS.put("티아민(mg)", "티아민");
		COLUMNS.put("리보플라빈(mg)", "리보플라빈");
		COLUMNS.put("니아신(mg)", "니아신");
		COLUMNS.put("비타민 C(mg)", "비타민 C");
		COLUMNS.put("비타민 D(μg)", "비타민 D");
		COLUMNS.put("콜레스테롤(mg)", "콜레스테롤");
		COLUMNS.put("포화지방산(g)", "총 포화 지방산");
		COLUMNS.put("트랜스지방산(g)", "총 트랜스 지방산");
		COLUMNS.put("폐기율(%)", "폐기율");
	}

	/** 보충 출처 식품인지 (표준데이터 식품코드는 "R211-931034001-0000"처럼 하이픈이 있다) */
	static boolean isSupplement(FoodRecord f) {
		return f.code().contains("-");
	}

	record Loaded(List<FoodRecord> api, List<FoodRecord> supplement) {
		List<FoodRecord> all() {
			List<FoodRecord> out = new ArrayList<>(api);
			out.addAll(supplement);
			return out;
		}
	}

	static Loaded load() throws IOException {
		return load(Collector.RAW.resolve("food-detail"), SUPPLEMENT);
	}

	static Loaded load(Path detailDir, Path supplementCsv) throws IOException {
		List<FoodRecord> api = FoodDetailParser.loadAll(detailDir);
		Set<String> codes = new HashSet<>();
		api.forEach(f -> codes.add(f.code()));
		List<FoodRecord> extra = supplement(supplementCsv).stream().filter(f -> codes.add(f.code())).toList();
		return new Loaded(api, extra);
	}

	static List<FoodRecord> loadAll() throws IOException {
		return load().all();
	}

	/** 수산물 행만, 지역·월별 표본은 대표값 하나로 */
	static List<FoodRecord> supplement(Path file) throws IOException {
		if (!Files.exists(file)) return List.of();
		Charset cp949 = Charset.forName("MS949");
		String headerLine = Files.readAllLines(file, cp949).getFirst().replace("﻿", "");
		List<String> header = Arrays.asList(headerLine.split(","));
		Map<String, List<String>> best = new LinkedHashMap<>();
		Map<String, Integer> bestRank = new HashMap<>();
		for (List<String> r : Csv.read(file, cp949)) {
			if (r.size() < header.size()) continue;
			String source = col(r, header, "출처명");
			if (source.contains("농촌진흥청")) continue; // API로 이미 받은 식품
			String name = name(r, header);
			int rank = rank(col(r, header, "식품명"), col(r, header, "생산·채취·포획월"));
			if (!best.containsKey(name) || rank < bestRank.get(name)) {
				best.put(name, r);
				bestRank.put(name, rank);
			}
		}
		List<FoodRecord> out = new ArrayList<>();
		best.forEach((name, r) -> out.add(toRecord(name, r, header)));
		return out;
	}

	/**
	 * 식품명: "참담치(홍합), 육, 생것". 종(중분류)이 대표식품명으로 끝나면 대표명을 별칭으로 붙인다:
	 * 다시마류/참다시마 → "참다시마(다시마)" — 레시피의 "다시마"가 찾을 수 있게
	 */
	static String name(List<String> r, List<String> header) {
		String group = col(r, header, "대표식품명");
		String species = col(r, header, "식품중분류명");
		if (blank(species)) species = group;
		String groupWord = group.endsWith("류") ? group.substring(0, group.length() - 1) : group;
		String plain = species.replaceAll("\\(.*?\\)", "");
		if (!plain.equals(groupWord) && plain.endsWith(groupWord) && !species.contains("(" + groupWord + ")")) {
			species = species + "(" + groupWord + ")";
		}
		List<String> parts = new ArrayList<>(List.of(species));
		for (String c : List.of("식품소분류명", "식품세분류명")) {
			String v = col(r, header, c);
			if (blank(v)) continue;
			// "양식,육" → 두 구간, "튀긴것(튀김옷, 수분많은 반죽)"의 괄호 안 쉼표는 구간 구분이 아니다
			for (String p : v.replaceAll(",\\s+(?=[^()]*\\))", "·").split(",")) {
				if (!blank(p) && !p.trim().equals("전체")) parts.add(p.trim());
			}
		}
		return String.join(", ", parts);
	}

	/** 대표·평균 > 대표 > 평균 > 지역·월별 */
	private static int rank(String fullName, String month) {
		boolean rep = fullName.contains("_대표");
		boolean avg = fullName.endsWith("평균");
		return (rep && avg ? 0 : rep ? 1 : avg ? 2 : 3) * 2 + (blank(month) ? 0 : 1);
	}

	private static FoodRecord toRecord(String name, List<String> r, List<String> header) {
		ValueType type = "분석".equals(col(r, header, "데이터생성방법명")) ? ValueType.MEASURED : ValueType.ESTIMATED;
		List<Nutrient> nutrients = new ArrayList<>();
		COLUMNS.forEach((column, nutrient) -> {
			String v = col(r, header, column);
			String unit = column.substring(column.indexOf('(') + 1, column.length() - 1);
			nutrients.add(blank(v)
					? new Nutrient(nutrient, null, unit, null, ValueType.NOT_MEASURED) // 빈 값은 0이 아니라 미측정
					: new Nutrient(nutrient, null, unit, Double.parseDouble(v.replace(",", "")), type));
		});
		String date = col(r, header, "데이터생성일자");
		return new FoodRecord(col(r, header, "식품코드"), name, col(r, header, "식품대분류명"), col(r, header, "출처명"),
				date.length() >= 4 ? date.substring(0, 4) : null, nutrients);
	}

	private static String col(List<String> r, List<String> header, String name) {
		int i = header.indexOf(name);
		return i < 0 || i >= r.size() ? "" : r.get(i).trim();
	}

	private static boolean blank(String s) {
		return s == null || s.isBlank() || s.equals("해당없음");
	}
}
