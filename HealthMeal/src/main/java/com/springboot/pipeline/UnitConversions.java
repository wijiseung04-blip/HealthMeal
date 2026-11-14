package com.springboot.pipeline;

import com.springboot.pipeline.ParsedIngredient.Status;

import java.util.*;

/**
 * 재료×단위 → 1단위당 g.
 * 값은 지어내지 않고 COOKRCP01 원문의 병기("달걀 50g(1개)", "식초 20g(1⅓큰술)")에서 계산한 중앙값만 쓴다.
 */
final class UnitConversions {

	record Entry(String name, String unit, double gramsPerUnit, int samples, double min, double max) {
	}

	/** 계량 정의: 1작은술 = 5ml, 1큰술 = 15ml, 1컵 = 200ml (한국 표준 계량컵) */
	static final Map<String, Double> VOLUME_ML = Map.of("ml", 1.0, "작은술", 5.0, "큰술", 15.0, "컵", 200.0);

	private final Map<String, Entry> byKey = new TreeMap<>();
	// 확인된 개당 중량 (data/mapping/unit-conversions-manual.csv, confirmed=O 사람 / AI 교차검토)
	private final Map<String, Double> manual = new HashMap<>();
	private final Set<String> aiOnly = new HashSet<>();
	// 같은 식품으로 매칭된 다른 재료명의 g/ml: "저염 간장" 큰술 → "간장"의 밀도
	private final Map<String, Entry> densityByName = new HashMap<>();

	static UnitConversions derive(List<ParsedIngredient> items) {
		Map<String, List<Double>> samples = new TreeMap<>();
		for (ParsedIngredient i : items) {
			if (i.status() != Status.GRAM || i.note() == null || i.grams() <= 0) continue;
			for (String piece : i.note().split(",\\s*")) {
				IngredientParser.Amount a = IngredientParser.amountOf(piece);
				if (a == null || a.unit() == null || a.amount() <= 0 || a.unit().equals("g")) continue;
				samples.computeIfAbsent(key(i.name(), a.unit()), k -> new ArrayList<>()).add(i.grams() / a.amount());
			}
		}
		// 부피 단위는 정의(작은술 5ml, 큰술 15ml, 컵 200ml)로 g/ml 하나로 합친다.
		// 같은 재료의 ml·작은술·큰술·컵 표본을 함께 써서 표본이 적은 단위도 환산되고, 1개짜리 이상값의 영향도 줄어든다
		Map<String, List<Double>> density = new TreeMap<>();
		Map<String, Set<String>> volumeUnits = new TreeMap<>();
		Map<String, List<Double>> pooled = new TreeMap<>();
		samples.forEach((k, v) -> {
			String[] nu = k.split("\u0000");
			Double ml = VOLUME_ML.get(nu[1]);
			if (ml == null) {
				pooled.put(k, v);
				return;
			}
			density.computeIfAbsent(nu[0], x -> new ArrayList<>()).addAll(v.stream().map(g -> g / ml).toList());
			volumeUnits.computeIfAbsent(nu[0], x -> new TreeSet<>()).add(nu[1]);
		});
		density.forEach((name, v) -> {
			volumeUnits.get(name).add("ml");
			for (String unit : volumeUnits.get(name)) {
				double ml = VOLUME_ML.get(unit);
				pooled.put(key(name, unit), new ArrayList<>(v.stream().map(d -> d * ml).toList()));
			}
		});
		samples = pooled;
		UnitConversions c = new UnitConversions();
		samples.forEach((k, v) -> {
			Collections.sort(v);
			double median = v.size() % 2 == 1 ? v.get(v.size() / 2) : (v.get(v.size() / 2 - 1) + v.get(v.size() / 2)) / 2;
			String[] nu = k.split("\u0000");
			c.byKey.put(k, new Entry(nu[0], nu[1], median, v.size(), v.getFirst(), v.getLast()));
		});
		return c;
	}

	/** match 단계가 저장한 환산표(data/mapping/unit-conversions.csv)를 읽는다. 웹에서 개인 레시피를 분석할 때 쓴다 */
	static UnitConversions fromCsv(java.nio.file.Path file) throws java.io.IOException {
		UnitConversions c = new UnitConversions();
		for (List<String> r : Csv.read(file)) {
			if (r.size() < 6) continue;
			c.byKey.put(key(r.get(0), r.get(1)), new Entry(r.get(0), r.get(1), Double.parseDouble(r.get(2)),
					(int) Double.parseDouble(r.get(3)), Double.parseDouble(r.get(4)), Double.parseDouble(r.get(5))));
		}
		return c;
	}

	/** 환산 가능하면 g, 아니면 null */
	Double toGrams(ParsedIngredient i) {
		if (i.grams() != null) return i.grams();
		if (i.amount() == null || i.unit() == null) return null;
		if (i.unit().equals("ml") && isWater(i.name())) return i.amount(); // 물은 1ml = 1g
		Entry e = byKey.get(key(i.name(), i.unit()));
		if (e != null) return i.amount() * e.gramsPerUnit();
		// 이 단위 표본은 없어도 같은 재료의 다른 부피 단위 표본이 있으면 g/ml로 환산
		Double ml = VOLUME_ML.get(i.unit());
		Entry perMl = ml == null ? null : byKey.getOrDefault(key(i.name(), "ml"), densityByName.get(i.name()));
		if (perMl != null) return i.amount() * ml * perMl.gramsPerUnit();
		Double perUnit = manual.get(key(FoodMatcher.compact(i.name()), i.unit()));
		return perUnit == null ? null : i.amount() * perUnit;
	}

	/** 확인된 개당 중량. 원문에서 도출한 값이 있으면 그것이 우선한다 */
	void addManual(String name, String unit, double gramsPerUnit) {
		addManual(name, unit, gramsPerUnit, false);
	}

	void addManual(String name, String unit, double gramsPerUnit, boolean byAi) {
		String k = key(FoodMatcher.compact(name), unit);
		manual.put(k, gramsPerUnit);
		if (byAi) aiOnly.add(k);
		else aiOnly.remove(k);
	}

	/** 이 항목의 g이 AI 교차검토만 거친 개당 중량에서 나왔는지 (원문 도출·밀도 환산이 먼저라 그때만 true) */
	boolean aiEstimated(ParsedIngredient i) {
		if (i.grams() != null || i.amount() == null || i.unit() == null) return false;
		if (byKey.containsKey(key(i.name(), i.unit()))) return false;
		if (VOLUME_ML.containsKey(i.unit()) && (byKey.containsKey(key(i.name(), "ml")) || densityByName.containsKey(i.name()))) return false;
		return aiOnly.contains(key(FoodMatcher.compact(i.name()), i.unit()));
	}

	/**
	 * 같은 식품코드로 매칭된 재료명끼리 밀도(g/ml)를 나눠 쓴다. 표본이 가장 많은 이름의 값을 쓴다.
	 * 부피 단위만: 밀도는 식품의 성질이지만 "개" 중량은 크기 표기마다 달라 공유하지 않는다.
	 */
	void linkSameFood(Map<String, String> foodCodeByName) {
		Map<String, Entry> best = new HashMap<>();
		foodCodeByName.forEach((name, code) -> {
			Entry e = byKey.get(key(name, "ml"));
			if (code != null && e != null) best.merge(code, e, (a, b) -> a.samples() >= b.samples() ? a : b);
		});
		foodCodeByName.forEach((name, code) -> {
			if (code != null && best.containsKey(code) && !byKey.containsKey(key(name, "ml"))) densityByName.put(name, best.get(code));
		});
	}

	// shortcut: 물·육수만 밀도 1로 본다. 다른 액체(기름·간장 등)는 병기 데이터나 환산표가 생기면 추가
	static boolean isWater(String name) {
		String n = name.replace(" ", "");
		return n.equals("물") || n.equals("생수") || n.equals("정수") || n.endsWith("육수") || n.endsWith("우린물") || n.endsWith("다시마물");
	}

	Collection<Entry> entries() {
		return byKey.values();
	}

	private static String key(String name, String unit) {
		return name + "\u0000" + unit;
	}
}
