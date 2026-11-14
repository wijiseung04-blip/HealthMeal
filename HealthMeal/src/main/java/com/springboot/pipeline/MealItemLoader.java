package com.springboot.pipeline;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 식사 조합 후보를 meal_items에 적재한다 (1회 제공량 기준).
 * - 편의점·간편식: 가공식품 표준데이터(data/raw/meals/processed-foods.csv)의 즉석식품·음료 중 1회분 포장(50~700g)
 * - 외식: 음식 표준데이터(data/raw/meals/dishes.csv)의 외식(분석·재료량 기반) 한 그릇 메뉴·국·찌개, 1인분 중량 기준
 * - 직접 조리: 공공 레시피(COOKRCP01)의 식약처 1인분 영양정보
 * - 밥: 쌀밥 1공기 210g (식약처 1회 제공량)을 음식 데이터의 쌀밥 100g당 값으로 계산
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MealItemLoader {

	static final Path DIR = Collector.RAW.resolve("meals");
	static final double RICE_BOWL_GRAMS = 210;

	public enum Situation { CONVENIENCE, EATING_OUT, HOME }

	public enum Slot { MAIN, STAPLE, SOUP, SIDE, DRINK }

	public record Item(Situation situation, Slot slot, String name, String maker, Double servingGrams,
					   Double energy, Double protein, Double fat, Double carbohydrate, Double sugar, Double sodium, String sourceCode) {
	}

	private static final Charset CP949 = Charset.forName("MS949");
	private static final Pattern WEIGHT = Pattern.compile("^\\s*([\\d.]+)\\s*(g|ml|mL|ML)\\s*$");
	/** 반찬 없이 밥만인 것: 주식이 아니라 밥 칸으로 쓴다 */
	private static final Pattern PLAIN_RICE = Pattern.compile("^(쌀밥|공기밥|공깃밥|흰밥|백미밥|즉석밥|\\S*잡곡밥|\\S*곡밥|현미밥|흑미밥|보리밥|팥밥|콩밥|햇반)");

	private final JdbcTemplate jdbc;

	public int load() throws IOException {
		return load(DIR.resolve("dishes.csv"), DIR.resolve("processed-foods.csv"), RecipeRaw.load(Collector.RAW.resolve("cookrcp01")));
	}

	@Transactional
	public int load(Path dishes, Path processed, List<JsonNode> recipes) throws IOException {
		List<Map<String, String>> dishRows = read(dishes);
		List<Item> items = new ArrayList<>();
		Item rice = riceBowl(dishRows);
		items.addAll(eatingOut(dishRows));
		items.addAll(convenience(read(processed)));
		items.addAll(home(recipes, computedNutrition(Path.of("data", "parsed", "recipe-nutrition.csv"))));
		if (rice != null) {
			items.add(rice);
			items.add(new Item(Situation.HOME, Slot.STAPLE, rice.name(), null, rice.servingGrams(), rice.energy(), rice.protein(),
					rice.fat(), rice.carbohydrate(), rice.sugar(), rice.sodium(), rice.sourceCode()));
		}

		jdbc.update("DELETE FROM meal_items");
		List<Object[]> rows = new ArrayList<>();
		for (Item i : items) {
			rows.add(new Object[]{i.situation().name(), i.slot().name(), cut(i.name(), 200), cut(i.maker(), 100), i.servingGrams(), i.energy(),
					i.protein(), i.fat(), i.carbohydrate(), i.sugar(), i.sodium(), i.sourceCode()});
		}
		jdbc.batchUpdate("INSERT INTO meal_items (situation, slot, name, maker, serving_grams, energy, protein, fat, carbohydrate, sugar, sodium, source_code) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", rows);
		jdbc.update("INSERT INTO data_versions (source, version, record_count, loaded_at) VALUES (?, ?, ?, ?)",
				"식사 조합 후보", LocalDate.now().toString(), items.size(), Timestamp.valueOf(LocalDateTime.now()));
		Map<String, Long> count = new TreeMap<>();
		items.forEach(i -> count.merge(i.situation() + "/" + i.slot(), 1L, Long::sum));
		log.info("식사 조합 후보 적재 완료: {}개 {}", items.size(), count);
		return items.size();
	}

	// ---- 외식 ----

	static List<Item> eatingOut(List<Map<String, String>> dishes) {
		List<Item> out = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (Map<String, String> r : dishes) {
			String origin = r.getOrDefault("식품기원명", "");
			if (!origin.startsWith("외식(분석") && !origin.startsWith("외식(재료량")) continue;
			String name = dishName(r.get("식품명"));
			Slot slot = dishSlot(r.getOrDefault("식품대분류명", ""), name);
			// 밥은 1공기로 따로 넣고, 반찬은 외식에서 따로 주문하지 않으므로 넣지 않는다
			if (slot == null || slot == Slot.STAPLE || slot == Slot.SIDE) continue;
			Double grams = weight(r.get("식품중량"));
			if (grams == null || grams < 30 || grams > 1500) continue;
			Item i = scaled(Situation.EATING_OUT, slot, name, null, grams, r);
			if (i != null && plausible(i) && seen.add(slot + name)) out.add(i);
		}
		return out;
	}

	static Slot dishSlot(String category, String name) {
		if (PLAIN_RICE.matcher(name).find()) return Slot.STAPLE;
		return switch (category) {
			case "밥류", "면 및 만두류", "죽 및 스프류" -> Slot.MAIN;
			case "국 및 탕류", "찌개 및 전골류" -> Slot.SOUP;
			case "볶음류", "구이류", "생채·무침류", "나물·숙채류", "조림류", "전·적 및 부침류", "찜류", "김치류", "튀김류" -> Slot.SIDE;
			default -> null;
		};
	}

	/** "김치찌개_돼지고기_두부" → "김치찌개(돼지고기·두부)" */
	static String dishName(String raw) {
		String[] parts = raw.trim().split("_");
		if (parts.length == 1) return parts[0];
		return parts[0] + "(" + String.join("·", Arrays.copyOfRange(parts, 1, parts.length)) + ")";
	}

	static Item riceBowl(List<Map<String, String>> dishes) {
		for (Map<String, String> r : dishes) {
			if ("쌀밥".equals(r.get("식품명").trim()) && r.getOrDefault("식품기원명", "").startsWith("외식(분석")) {
				return scaled(Situation.EATING_OUT, Slot.STAPLE, "쌀밥 1공기", null, RICE_BOWL_GRAMS, r);
			}
		}
		return null;
	}

	// ---- 편의점·간편식 ----

	private static final Set<String> CONVENIENCE_MAIN = Set.of("주먹밥/김밥/초밥", "도시락", "샌드위치", "버거", "즉석 면요리", "죽", "밥류");
	private static final Set<String> CONVENIENCE_SIDE = Set.of("샐러드");
	private static final Set<String> CONVENIENCE_DRINK = Set.of("우유", "가공우유", "강화우유", "유당분해우유", "두유", "발효유", "농후발효유",
			"액상차", "탄산수", "과·채주스");

	static List<Item> convenience(List<Map<String, String>> processed) {
		List<Item> out = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (Map<String, String> r : processed) {
			String group = r.getOrDefault("식품대분류명", "");
			String rep = r.getOrDefault("대표식품명", "");
			String name = r.get("식품명").trim();
			Slot slot;
			if (group.equals("즉석식품류") && CONVENIENCE_MAIN.contains(rep)) slot = PLAIN_RICE.matcher(name).find() ? null : Slot.MAIN;
			else if (group.equals("즉석식품류") && CONVENIENCE_SIDE.contains(rep)) slot = Slot.SIDE;
			else if (group.equals("알가공품류")) slot = Slot.SIDE;
			else if ((group.equals("음료류") || group.equals("유가공품류")) && CONVENIENCE_DRINK.contains(rep)) slot = Slot.DRINK;
			else slot = null;
			if (slot == null) continue;
			if (NOT_READY.matcher(name).find()) continue;
			Double grams = weight(r.get("식품중량"));
			// 1회분 포장만: 대용량(업소용)·소포장 조각은 뺀다. 음료는 액체(ml)만
			double min = slot == Slot.MAIN ? 80 : 50;
			double max = slot == Slot.DRINK ? 500 : 700;
			if (grams == null || grams < min || grams > max) continue;
			if (slot == Slot.DRINK && !Objects.toString(r.get("식품중량"), "").toLowerCase().endsWith("ml")) continue;
			String maker = Objects.toString(r.get("제조사명"), "").trim();
			Item i = scaled(Situation.CONVENIENCE, slot, name, maker.isEmpty() || maker.equals("해당없음") ? null : maker, grams, r);
			if (i == null || !plausible(i)) continue;
			if (seen.add(slot + name)) out.add(i);
		}
		return out;
	}

	// ---- 직접 조리 ----

	/** 우리 계산 엔진의 레시피 전체 합계(모든 재료 계산된 것만): 번호 → {열량, 나트륨} */
	record Computed(double energy, double sodium) {
	}

	static Map<String, Computed> computedNutrition(Path file) throws IOException {
		Map<String, Computed> out = new HashMap<>();
		for (List<String> r : Csv.read(file)) {
			// recipe_seq, recipe_name, 에너지, 탄수화물, 당류, 단백질, 지방, 포화지방, 나트륨, 식이섬유, 콜레스테롤, sodium_coverage, computed, total, ...
			if (r.size() < 14 || !r.get(12).equals(r.get(13)) || r.get(2).isBlank() || r.get(8).isBlank()) continue;
			out.put(r.get(0), new Computed(Double.parseDouble(r.get(2)), Double.parseDouble(r.get(8))));
		}
		return out;
	}

	/**
	 * 식약처 레시피의 1인분 영양정보. 원본에 오류가 섞여 있어(나트륨 2mg인 묵은지 요리, 단백질 858g 등) 검증을 통과한 것만 쓴다:
	 * 열량이 탄단지 환산(4·4·9)과 35% 넘게 어긋나면 / 짠 요리인데 나트륨 20mg 미만이면 /
	 * 우리 엔진 값을 같은 열량으로 맞췄을 때 나트륨이 4배 넘게 차이 나면 제외
	 */
	static List<Item> home(List<JsonNode> recipes, Map<String, Computed> computed) {
		List<Item> out = new ArrayList<>();
		for (JsonNode r : recipes) {
			Slot slot = switch (r.path("RCP_PAT2").asString("")) {
				case "밥", "일품" -> Slot.MAIN;
				case "국&찌개" -> Slot.SOUP;
				case "반찬" -> Slot.SIDE;
				default -> null; // 후식·기타는 한 끼 구성에서 뺀다
			};
			Double energy = num(r.path("INFO_ENG").asString(""));
			Double sodium = num(r.path("INFO_NA").asString(""));
			Double protein = num(r.path("INFO_PRO").asString(""));
			Double fat = num(r.path("INFO_FAT").asString(""));
			Double carb = num(r.path("INFO_CAR").asString(""));
			if (slot == null || energy == null || sodium == null || protein == null || fat == null || carb == null || energy <= 0) continue;
			if (Math.abs(4 * protein + 4 * carb + 9 * fat - energy) > 0.35 * energy) continue;
			if (sodium < 20) continue;
			String seq = r.path("RCP_SEQ").asString("");
			Computed c = computed.get(seq);
			if (c != null && c.energy() > 0) {
				double perServing = c.sodium() / (c.energy() / energy);
				if (perServing > 100 && (sodium < perServing / 4 || sodium > perServing * 4)) continue;
			}
			out.add(new Item(Situation.HOME, slot, r.path("RCP_NM").asString("").trim(), null, num(r.path("INFO_WGT").asString("")),
					energy, protein, fat, carb, null, sodium, seq));
		}
		return out;
	}

	// ---- 공통 ----

	/** 조리 전 재료·분말은 바로 먹는 한 끼가 아니다 */
	private static final Pattern NOT_READY = Pattern.compile("밀키트|분말|파우더|생쌀|쌀\\s*\\d+kg");

	/**
	 * 한 끼로 볼 만한 것만: 주식은 열량 200kcal·나트륨 100mg 이상(밥·잡곡밥만인 것 제외),
	 * 곁들임은 400kcal 이하, 음료는 250kcal 이하
	 */
	static boolean plausible(Item i) {
		return switch (i.slot()) {
			case MAIN -> i.energy() >= 200 && i.sodium() >= 100;
			case SIDE -> i.energy() <= 400;
			case DRINK -> i.energy() <= 250;
			default -> true;
		};
	}

	/** 100g(ml)당 값 × 1회 제공량. 열량·나트륨이 없으면 조합 조건을 확인할 수 없어 뺀다 */
	private static Item scaled(Situation s, Slot slot, String name, String maker, double grams, Map<String, String> r) {
		Double energy = per(r, "에너지(kcal)", grams);
		Double sodium = per(r, "나트륨(mg)", grams);
		if (energy == null || sodium == null || energy <= 0) return null;
		return new Item(s, slot, name, maker, grams, energy, per(r, "단백질(g)", grams), per(r, "지방(g)", grams),
				per(r, "탄수화물(g)", grams), per(r, "당류(g)", grams), sodium, r.get("식품코드"));
	}

	private static Double per(Map<String, String> r, String column, double grams) {
		Double v = num(r.get(column));
		return v == null ? null : Math.round(v * grams / 100 * 10) / 10.0;
	}

	static Double weight(String s) {
		if (s == null) return null;
		Matcher m = WEIGHT.matcher(s);
		return m.matches() ? Double.valueOf(m.group(1)) : null;
	}

	private static Double num(String s) {
		if (s == null || s.isBlank()) return null;
		try {
			return Double.valueOf(s.replace(",", "").trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** 표준데이터 CSV(CP949)를 열 이름 → 값으로 읽는다 */
	static List<Map<String, String>> read(Path file) throws IOException {
		if (!Files.exists(file)) throw new IllegalStateException(file + "가 없습니다. 음식·가공식품 표준데이터 CSV를 data/raw/meals/에 두세요.");
		List<String> header = Arrays.asList(Files.readAllLines(file, CP949).getFirst().replace("﻿", "").split(","));
		List<Map<String, String>> out = new ArrayList<>();
		for (List<String> r : Csv.read(file, CP949)) {
			Map<String, String> m = new HashMap<>();
			for (int i = 0; i < header.size() && i < r.size(); i++) m.put(header.get(i).trim(), r.get(i));
			out.add(m);
		}
		return out;
	}

	private static String cut(String v, int max) {
		return v == null || v.length() <= max ? v : v.substring(0, max);
	}
}
