package com.springboot.pipeline;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 레시피 재료명 → 국가표준식품 코드.
 * 등급: EXACT(이름 그대로 일치) / MANUAL_CONFIRMED(사람이 확인) / SIMILAR(규칙으로 추정, 확인 필요) / UNMATCHED
 */
final class FoodMatcher {

	enum Grade { EXACT, MANUAL_CONFIRMED, SIMILAR, UNMATCHED }

	record Match(String ingredient, Grade grade, String foodCode, String foodName, String rule, List<String> candidates) {
	}

	/** 물은 식품 DB에 없지만 영양성분이 0이므로 가상 코드로 처리한다 */
	static final String WATER_CODE = "WATER";

	// 재료명 앞의 조리 상태 → 식품명 상태 표기
	private static final Map<String, String> STATE_PREFIX = Map.of(
			"다진", "다진것", "삶은", "삶은것", "데친", "데친것", "구운", "구운것", "볶은", "볶은것",
			"말린", "말린것", "마른", "말린것", "건", "말린것", "찐", "찐것");
	// 식품 선택에 영향 없는 수식어
	private static final List<String> QUALIFIERS = List.of("저염", "무가당", "국산", "유기농", "냉동", "손질한", "깐", "불린", "다진것", "채썬", "슬라이스");
	// 재료로 쓸 때 기본값이 되면 안 되는 가공·조리 형태
	private static final java.util.regex.Pattern PROCESSED = java.util.regex.Pattern.compile(
			"젓갈|액젓|시리얼|과자|사탕|음료|통조림|조미|훈제|튀긴것|양념|절임|장아찌|빵,|스낵|초콜릿|육수|주스|레토르트");
	// 영양이 크게 달라지는 형태: 재료명에 그 표기가 없으면 이것으로 대신하지 않는다 (낙지 ≠ 낙지 양념젓갈)
	private static final java.util.regex.Pattern TRANSFORMED = java.util.regex.Pattern.compile("젓갈|액젓|튀긴것|과자");
	// 색 접두어 → 식품명 색 표기
	private static final Map<String, String> COLOR_PREFIX = Map.of(
			"홍", "빨간색", "빨간", "빨간색", "빨강", "빨간색", "적", "빨간색",
			"청", "초록색", "초록", "초록색", "노랑", "노란색", "노란", "노란색", "주황", "주황색");
	// 레시피 표기 → 식품명 표기. 같은 식품의 다른 이름만 둔다
	private static final Map<String, String> SYNONYMS = new LinkedHashMap<>();
	static {
		SYNONYMS.put("쇠고기", "소고기");
		SYNONYMS.put("요거트", "요구르트");
		SYNONYMS.put("계란", "달걀");
		SYNONYMS.put("노른자", "난황");
		SYNONYMS.put("흰자", "난백");
		SYNONYMS.put("녹말", "전분");
		SYNONYMS.put("와사비", "고추냉이");
	}
	private static final Set<String> STATE_WORDS =Set.of("생것", "말린것", "삶은것", "데친것", "구운것", "볶은것", "찐것", "가루", "다진것");

	private final Map<String, FoodRecord> byFullName = new HashMap<>();
	private final Map<String, List<FoodRecord>> byBase = new HashMap<>();
	private final Map<String, List<FoodRecord>> bySegment = new HashMap<>();
	private final Map<String, String[]> manual; // 재료명 → {식품코드, 확인여부}
	private final Map<String, FoodRecord> byCode = new HashMap<>();

	// 보충 출처(수산물 표준데이터)는 국가표준식품에서 후보를 못 찾았을 때만 쓴다. 기존 매칭을 바꾸지 않는다
	private final FoodMatcher fallback;

	FoodMatcher(List<FoodRecord> foods, Map<String, String[]> manual) {
		this(foods.stream().filter(f -> !FoodSources.isSupplement(f)).toList(), foods, manual);
	}

	private FoodMatcher(List<FoodRecord> primary, List<FoodRecord> all, Map<String, String[]> manual) {
		this.manual = manual;
		this.fallback = primary.size() < all.size() ? new FoodMatcher(all, all, manual) : null;
		for (FoodRecord f : all) byCode.put(f.code(), f); // 수동 지정은 어느 출처 코드든 쓸 수 있다
		for (FoodRecord f : primary) {
			byFullName.putIfAbsent(compact(f.name()), f);
			String[] segs = f.name().split(",");
			for (String alias : names(segs[0])) byBase.computeIfAbsent(alias, k -> new ArrayList<>()).add(f);
			for (int i = 1; i < segs.length; i++) {
				if (STATE_WORDS.contains(segs[i].trim())) continue;
				for (String alias : names(segs[i])) bySegment.computeIfAbsent(alias, k -> new ArrayList<>()).add(f);
			}
		}
	}

	Match match(String ingredient) {
		Match m = matchHere(ingredient);
		if (m.grade() != Grade.UNMATCHED || fallback == null) return m;
		Match f = fallback.match(ingredient);
		return f.grade() == Grade.UNMATCHED ? m : f;
	}

	private Match matchHere(String ingredient) {
		String n = compact(ingredient);
		String[] m = manual.get(n);
		if (m != null && byCode.containsKey(m[0]) || m != null && m[0].equals(WATER_CODE)) {
			Grade g = "O".equalsIgnoreCase(m[1]) ? Grade.MANUAL_CONFIRMED : Grade.SIMILAR;
			return new Match(ingredient, g, m[0], m[0].equals(WATER_CODE) ? "물" : byCode.get(m[0]).name(), "수동 지정", List.of());
		}
		if (UnitConversions.isWater(n)) return new Match(ingredient, Grade.EXACT, WATER_CODE, "물", "물(영양성분 0)", List.of());

		FoodRecord full = byFullName.get(n);
		if (full != null) return new Match(ingredient, Grade.EXACT, full.code(), full.name(), "식품명 일치", List.of());

		// 상태 접두어와 수식어를 떼어낸 핵심어
		String state = null;
		String core = n;
		// "건포도"처럼 상태 접두어가 붙은 채로 식품명인 것은 떼지 않는다
		boolean knownAsIs = byBase.containsKey(n) || bySegment.containsKey(n);
		for (var e : STATE_PREFIX.entrySet()) {
			if (!knownAsIs && core.startsWith(e.getKey()) && core.length() > e.getKey().length()) {
				state = e.getValue();
				core = core.substring(e.getKey().length());
			}
		}
		for (String q : QUALIFIERS) core = core.replace(q, "");
		if (core.isEmpty()) core = n;
		for (var e : SYNONYMS.entrySet()) core = core.replace(e.getKey(), e.getValue());
		// "돼지등심"·"닭다리살" → 식품명은 "돼지고기", "닭고기"
		core = core.replaceAll("^(돼지|닭)(?!고기)", "$1고기");
		// "홍고추" → 고추 + 빨간색 (해당 색 식품이 있을 때만)
		for (var e : COLOR_PREFIX.entrySet()) {
			String rest = core.substring(Math.min(e.getKey().length(), core.length()));
			if (core.startsWith(e.getKey()) && rest.length() >= 2 && byBase.containsKey(rest)
					&& byBase.get(rest).stream().anyMatch(f -> hasSegment(f, e.getValue()))) {
				List<FoodRecord> colored = byBase.get(rest).stream().filter(f -> hasSegment(f, e.getValue())).toList();
				FoodRecord pick = pick(colored, rest, state);
				return new Match(ingredient, Grade.SIMILAR, pick.code(), pick.name(), "색 표기(" + e.getValue() + ")", top(byBase.get(rest)));
			}
		}

		List<FoodRecord> pool = byBase.get(core);
		if (pool != null) {
			FoodRecord pick = pick(pool, core, state);
			if (transformedOnly(pick, n)) return new Match(ingredient, Grade.UNMATCHED, null, null, "가공 형태만 있음", top(pool));
			if (notDried(pick, state)) return new Match(ingredient, Grade.UNMATCHED, null, null, "말린 형태 없음", top(pool));
			boolean exact = core.equals(n) && (pick.name().equals(core) || hasSegment(pick, "생것"));
			return new Match(ingredient, exact ? Grade.EXACT : Grade.SIMILAR, pick.code(), pick.name(),
					core.equals(n) ? "대표명 일치" : "수식어 제거 후 대표명 일치", top(pool));
		}
		pool = bySegment.get(core);
		if (pool != null) {
			FoodRecord pick = pick(pool, core, state);
			if (transformedOnly(pick, n)) return new Match(ingredient, Grade.UNMATCHED, null, null, "가공 형태만 있음", top(pool));
			if (notDried(pick, state)) return new Match(ingredient, Grade.UNMATCHED, null, null, "말린 형태 없음", top(pool));
			return new Match(ingredient, Grade.SIMILAR, pick.code(), pick.name(), "세부명 일치", top(pool));
		}
		if (core.endsWith("가루") && byBase.containsKey(core.substring(0, core.length() - 2))) {
			// 가루는 말린 것을 간 것: 가루 > 말린것. 생것으로 대신하면 농도가 몇 배 차이 나므로 없으면 매칭하지 않는다
			List<FoodRecord> p = byBase.get(core.substring(0, core.length() - 2));
			for (String form : List.of("가루", "말린것")) {
				List<FoodRecord> dry = p.stream().filter(f -> Arrays.stream(f.name().split(","))
						.anyMatch(seg -> seg.trim().endsWith(form))).toList(); // "강력밀가루", "삶아서 말린것"
				if (!dry.isEmpty()) {
					FoodRecord pick = pick(dry, core, form);
					return new Match(ingredient, Grade.SIMILAR, pick.code(), pick.name(), "가루 표기", top(p));
				}
			}
			return new Match(ingredient, Grade.UNMATCHED, null, null, "가루·말린 형태 없음", top(p));
		}
		// "소고기등심", "달걀난황", "닭고기다리살" → 대표명 + 세부 부위. 나머지가 세부명과 정확히 같을 때만 (마늘기름 ≠ 마늘)
		for (int i = core.length() - 1; i >= 2; i--) {
			List<FoodRecord> p = byBase.get(core.substring(0, i));
			if (p == null) continue;
			String rest = core.substring(i);
			String part = rest.endsWith("살") && rest.length() > 1 ? rest.substring(0, rest.length() - 1) : rest;
			List<FoodRecord> withPart = p.stream().filter(f -> hasSegmentName(f, rest) || hasSegmentName(f, part)).toList();
			if (!withPart.isEmpty()) {
				FoodRecord pick = pick(withPart, core, state);
				return new Match(ingredient, Grade.SIMILAR, pick.code(), pick.name(), "대표명+세부명(" + core.substring(0, i) + ", " + rest + ")", top(withPart));
			}
		}
		// 한국어 합성어는 끝말이 중심어: "칵테일새우" → "새우"
		for (int i = 1; i <= core.length() - 2; i++) {
			List<FoodRecord> p = byBase.get(core.substring(i));
			if (p != null) {
				FoodRecord pick = pick(p, core.substring(i), state);
				if (notDried(pick, state)) return new Match(ingredient, Grade.UNMATCHED, null, null, "말린 형태 없음", top(p));
				return new Match(ingredient, Grade.SIMILAR, pick.code(), pick.name(), "끝말 일치(" + core.substring(i) + ")", top(p));
			}
		}
		return new Match(ingredient, Grade.UNMATCHED, null, null, "후보 없음", List.of());
	}

	/** 상태가 맞는 것 > 가공품 아님 > 이름 그대로 > 생것 > 쉼표 구간이 적은 것 > 코드순 */
	private static FoodRecord pick(List<FoodRecord> pool, String core, String state) {
		return pool.stream().min(Comparator
				.comparing((FoodRecord f) -> state != null && hasSegment(f, state) ? 0 : 1)
				.thenComparing(f -> PROCESSED.matcher(f.name()).find() ? 1 : 0)
				.thenComparing(f -> compact(f.name()).equals(core) ? 0 : 1)
				.thenComparing(f -> hasSegment(f, "생것") ? 0 : 1)
				.thenComparing(f -> f.name().split(",").length)
				.thenComparing(FoodRecord::code)).orElseThrow();
	}

	/** 고른 식품이 젓갈·튀김 등인데 재료명에는 그런 표기가 없음 (pick은 가공품을 뒤로 미루므로 가공품뿐일 때만 해당) */
	private static boolean transformedOnly(FoodRecord pick, String ingredient) {
		var m = TRANSFORMED.matcher(pick.name());
		return m.find() && !ingredient.contains("젓") && !ingredient.contains("튀") && !ingredient.contains("과자");
	}

	/** "건·말린·마른" 재료인데 말린 식품이 없음: 생것으로 대신하면 같은 g에 영양이 몇 배 차이 난다 */
	private static boolean notDried(FoodRecord pick, String state) {
		return "말린것".equals(state) && !pick.name().contains("말린") && !pick.name().contains("건조") && !pick.name().contains("가루");
	}

	private static boolean hasSegment(FoodRecord f, String seg) {
		return Arrays.stream(f.name().split(",")).anyMatch(s -> s.trim().equals(seg));
	}

	/** 쉼표 구간의 이름(괄호 별칭 포함)이 같은지: "다리(넓적다리)" → 다리, 넓적다리 */
	private static boolean hasSegmentName(FoodRecord f, String name) {
		String[] segs = f.name().split(",");
		for (int i = 1; i < segs.length; i++) if (names(segs[i]).contains(name)) return true;
		return false;
	}

	private static List<String> top(List<FoodRecord> pool) {
		return pool.stream().limit(5).map(f -> f.code() + " " + f.name()).collect(Collectors.toList());
	}

	/** "큰느타리버섯(새송이버섯)" → [큰느타리버섯, 새송이버섯] */
	private static List<String> names(String segment) {
		String s = segment.trim();
		List<String> out = new ArrayList<>();
		out.add(compact(s.replaceAll("\\(.*?\\)", "")));
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\(([^)]+)\\)").matcher(s);
		while (m.find()) out.add(compact(m.group(1)));
		return out;
	}

	static String compact(String s) {
		return s.replaceAll("\\s+", "");
	}
}
