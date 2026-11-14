package com.springboot.nutrition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 기름·설탕류 재료가 이 레시피에서 무슨 역할인지 조리 순서 문장으로 판단한다. AI를 쓰지 않는다.
 * 재료가 나오는 문장에서 재료 바로 뒤(약 14자)의 동사를 먼저 보고, 없으면 문장 전체를 본다.
 * 줄여도 조리가 되는 역할(조리용 기름, 맛 내기)만 감량 후보를 만들고, 나머지는 감량하지 않는다.
 */
public final class IngredientRoleClassifier {

	public enum Role {
		COOKING_MEDIUM("볶음·부침용 기름", true),
		FLAVOR("맛 내기(양념·소스·드레싱)", true),
		DEEP_FRY("튀김 기름", false),
		STRUCTURE("반죽·머랭 등 구조", false),
		UNKNOWN("조리 순서에서 역할을 찾지 못함", false);

		private final String label;
		private final boolean reducible;

		Role(String label, boolean reducible) {
			this.label = label;
			this.reducible = reducible;
		}

		public String label() {
			return label;
		}

		public boolean reducible() {
			return reducible;
		}
	}

	/** evidence: 판단에 쓴 조리 순서 문장(공백 제거 전 원문) */
	public record Classification(Role role, String evidence) {
	}

	public static final String OIL_GROUP = "유지류";
	public static final String SUGAR_GROUP = "당류";

	// 강한 단서: 재료 뒤에서 가장 먼저 나오는 것으로 역할을 정한다
	private static final Pattern FRY = Pattern.compile("튀기|튀겨|튀김|튀긴");
	private static final Pattern STRUCTURE = Pattern.compile(
			"반죽|발효시|발효한|머랭|휘핑|오븐|캐러멜|베이킹|박력분|강력분|이스트|크림을만|타르트|쿠키|머핀");
	private static final Pattern MEDIUM = Pattern.compile("두르|두른|둘러|볶|부치|부친|부쳐|지지|지진|지져|구워|구운|굽|바르|발라|익히|익혀|익힌");
	private static final Pattern FLAVOR = Pattern.compile(
			"양념|소스|드레싱|버무|무쳐|무치|무친|절여|절이|조려|조림|졸여|졸인|뿌려|뿌린|올려|올린|곁들|마리네이드|재워|재운|간을|밑간");
	// 약한 단서: 강한 단서가 없을 때만 맛 내기로 본다
	private static final Pattern WEAK = Pattern.compile("넣|섞|끓|타서|풀어|녹여|녹인");
	// 재료명이 문장에 그대로 없을 때 찾는 일반 표기 ("식용유" → "기름을 두르고")
	private static final List<String> OIL_WORDS = List.of("기름", "오일", "버터");
	private static final List<String> SUGAR_WORDS = List.of("설탕", "올리고당", "꿀", "시럽", "물엿", "조청");
	private static final int WINDOW = 14;

	private IngredientRoleClassifier() {
	}

	public static boolean applies(String foodGroup) {
		return OIL_GROUP.equals(foodGroup) || SUGAR_GROUP.equals(foodGroup);
	}

	public static Classification classify(String ingredient, String foodGroup, List<String> steps) {
		boolean oil = OIL_GROUP.equals(foodGroup);
		List<String> sentences = sentences(steps);
		String name = compact(ingredient);
		boolean named = sentences.stream().anyMatch(s -> compact(s).contains(name));
		List<String> keys = named ? List.of(name) : oil ? OIL_WORDS : SUGAR_WORDS;

		List<String> hits = new ArrayList<>();
		List<String> windows = new ArrayList<>();  // 재료 바로 뒤
		List<String> rests = new ArrayList<>();    // 재료부터 문장 끝
		List<String> wholes = new ArrayList<>();   // 문장 전체
		for (String s : sentences) {
			String c = compact(s);
			int i = find(c, keys);
			if (i < 0) continue;
			hits.add(s.trim());
			windows.add(c.substring(i, Math.min(c.length(), i + WINDOW + 4)));
			rests.add(c.substring(i));
			wholes.add(c);
		}
		if (hits.isEmpty()) return new Classification(Role.UNKNOWN, null);
		String evidence = String.join(" / ", hits);

		// 줄이면 안 되는 쪽을 먼저: 튀김 기름, 또는 재료가 나온 문장의 뒷부분에 튀김·반죽 단서
		if (oil && name.contains("튀김")) return new Classification(Role.DEEP_FRY, evidence);
		// 재료 바로 뒤가 "두르고·볶아"면 팬 기름: 같은 문장의 반죽(부침개)은 기름의 역할이 아니다. 튀김만 확인
		boolean panOil = oil && windows.stream().map(IngredientRoleClassifier::earliestCue).filter(java.util.Objects::nonNull)
				.findFirst().orElse(null) == Role.COOKING_MEDIUM;
		for (String sentenceRest : rests) {
			// 같은 절까지만: "소스를 만들고, 오븐에 구운 삼겹살"의 오븐은 설탕과 상관없다
			String rest = sentenceRest.split("(?<=[고며서후뒤]),", 2)[0];
			if (oil && FRY.matcher(rest).find()) return new Classification(Role.DEEP_FRY, evidence);
			if (!panOil && STRUCTURE.matcher(rest).find()) return new Classification(Role.STRUCTURE, evidence);
		}
		// 판단 순서: 재료 바로 뒤 → 재료부터 문장 끝 → 문장 전체.
		// 문장마다 가장 먼저 나오는 강한 단서로 역할을 정하고, 튀김·구조가 하나라도 있으면 그것(줄이지 않는 쪽), 아니면 첫 문장의 역할
		for (List<String> scope : List.of(windows, rests, wholes)) {
			Role first = null;
			for (String text : scope) {
				Role r = earliestCue(text);
				if (r == Role.DEEP_FRY || r == Role.STRUCTURE) return new Classification(forGroup(r, oil), evidence);
				if (first == null) first = r;
			}
			if (first != null) return new Classification(forGroup(first, oil), evidence);
		}
		// 강한 단서가 없으면: 팬·냄비에 기름을 넣으면 조리용, 그 밖에 넣고·섞으면 맛 내기
		if (oil && wholes.stream().anyMatch(c -> c.contains("팬") || c.contains("냄비"))) return new Classification(Role.COOKING_MEDIUM, evidence);
		if (rests.stream().anyMatch(c -> WEAK.matcher(c).find())) return new Classification(Role.FLAVOR, evidence);
		return new Classification(Role.UNKNOWN, evidence);
	}

	private static Role earliestCue(String text) {
		Role role = null;
		int best = Integer.MAX_VALUE;
		for (var e : List.of(Map.entry(FRY, Role.DEEP_FRY), Map.entry(STRUCTURE, Role.STRUCTURE),
				Map.entry(MEDIUM, Role.COOKING_MEDIUM), Map.entry(FLAVOR, Role.FLAVOR))) {
			var m = e.getKey().matcher(text);
			if (m.find() && m.start() < best) {
				best = m.start();
				role = e.getValue();
			}
		}
		return role;
	}

	/** 설탕류는 볶음·튀김 문장에 있어도 맛 내기 (튀김 소스, 볶다가 설탕) */
	private static Role forGroup(Role role, boolean oil) {
		if (oil) return role;
		return role == Role.COOKING_MEDIUM || role == Role.DEEP_FRY ? Role.FLAVOR : role;
	}

	/** 재료 표기 위치. "기름기"(고기의 기름)는 재료가 아니다 */
	private static int find(String sentence, List<String> keys) {
		for (String k : keys) {
			int from = 0;
			while (true) {
				int i = sentence.indexOf(k, from);
				if (i < 0) break;
				if (!(k.equals("기름") && sentence.startsWith("기름기", i))) return i;
				from = i + 1;
			}
		}
		return -1;
	}

	/** 마침표로 문장을 나눈다. "1.5컵", "170.5℃"의 소수점은 나누지 않는다 */
	static List<String> sentences(List<String> steps) {
		String joined = String.join(" ", steps).replace('\n', ' ');
		List<String> out = new ArrayList<>();
		for (String s : joined.split("(?<![0-9])\\.(?![0-9])")) {
			if (!s.isBlank()) out.add(s.replaceAll("\\s+", " "));
		}
		return out;
	}

	private static String compact(String s) {
		return s.replaceAll("\\s+", "");
	}
}
