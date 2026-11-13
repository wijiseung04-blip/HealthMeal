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

	private final Map<String, Entry> byKey = new TreeMap<>();

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
		// 계량 정의상 1큰술 = 3작은술: 같은 재료의 큰술·작은술 표본을 합쳐 표본 1개짜리 이상값의 영향을 줄인다
		Map<String, List<Double>> pooled = new TreeMap<>();
		samples.forEach((k, v) -> pooled.computeIfAbsent(k, x -> new ArrayList<>()).addAll(v));
		samples.forEach((k, v) -> {
			String[] nu = k.split("\u0000");
			if (nu[1].equals("작은술")) pooled.computeIfAbsent(key(nu[0], "큰술"), x -> new ArrayList<>()).addAll(v.stream().map(g -> g * 3).toList());
			if (nu[1].equals("큰술")) pooled.computeIfAbsent(key(nu[0], "작은술"), x -> new ArrayList<>()).addAll(v.stream().map(g -> g / 3).toList());
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

	/** 환산 가능하면 g, 아니면 null */
	Double toGrams(ParsedIngredient i) {
		if (i.grams() != null) return i.grams();
		if (i.amount() == null || i.unit() == null) return null;
		if (i.unit().equals("ml") && isWater(i.name())) return i.amount(); // 물은 1ml = 1g
		Entry e = byKey.get(key(i.name(), i.unit()));
		return e == null ? null : i.amount() * e.gramsPerUnit();
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
