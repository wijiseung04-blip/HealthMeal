package com.springboot.pipeline;

import com.springboot.pipeline.ParsedIngredient.Status;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * COOKRCP01 RCP_PARTS_DTLS 자유 텍스트를 재료 항목으로 나눈다.
 * 규칙 기반이며 원문에 없는 수량은 만들지 않는다.
 */
public final class IngredientParser {

	private static final String NUM = "(\\d+(?:\\.\\d+)?(?:\\s*/\\s*\\d+)?[½⅓⅔¼¾⅛]?|[½⅓⅔¼¾⅛])";
	private static final String RANGE = NUM + "(?:\\s*[~∼-]\\s*" + NUM + ")?";
	private static final String UNIT = "([a-zA-Z㎖ℓ]+|[가-힣]{1,3})?";
	// 이름 끝에 붙은 수량: "올리브유 12", "새송이버섯 100g", "와사비 갠 것1", "2g씩"
	private static final Pattern TRAILING_AMOUNT = Pattern.compile("^(.*?)\\s*" + RANGE + "\\s*" + UNIT + "\\s*씩?\\s*$");
	// 괄호 안이 수량만인 경우: "(30g)", "(3/4모)", "(1.5Ts)"
	private static final Pattern PURE_AMOUNT = Pattern.compile("^\\s*" + RANGE + "\\s*" + UNIT + "\\s*씩?\\s*$");
	private static final Pattern VAGUE = Pattern.compile("약간|적당량|적당히|조금|소량|기호에\\s*따라|취향껏");
	private static final Pattern BULLET = Pattern.compile("^[●•·◆■□▶\\-*※\\s]+");
	private static final Pattern ITEM_THEN_LABEL = Pattern.compile("^(.*\\d[a-zA-Z가-힣㎖]*\\)?)\\s+([^\\d]+)$");
	private static final Pattern NON_FOOD = Pattern.compile("꼬치$|막대기|이쑤시개|유산지|종이호일|쿠킹호일|꼬챙이");
	private static final Pattern SERVING_NOTE =Pattern.compile("^\\d+\\s*인분\\s*기준$");
	// 쉼표 없이 이어진 재료: "떡볶이떡 40g 고추장 8g", "로즈마리 1g. 마늘기름 15g"
	private static final Pattern MISSING_COMMA = Pattern.compile(
			"(\\d\\s*(?:g|kg|ml|cc|㎖|개|큰술|작은술|Ts|ts|장|마리|알|쪽|컵)(?:\\([^()]*\\))?)\\s*\\.?\\s+(?=[가-힣])");
	// 띄어쓰기도 없이 붙은 재료: "흰후춧가루 0.1g레몬즙 5g" (단, 2g씩·1g정도는 제외)
	private static final Pattern MISSING_SPACE = Pattern.compile("(\\d\\s*(?:g|ml))(?=[가-힣])(?!씩|정도|분량|짜리)");
	private static final Pattern LABEL_WORD = Pattern.compile(".*(재료|양념장|양념|소스|육수|드레싱|고명|국물|밑간|촛물|장식)");
	private static final Pattern NESTED = Pattern.compile("\\(([^()]*)\\(([^()]*)\\)([^()]*)\\)");
	private static final Pattern BRACKET_TAG =Pattern.compile("\\[[^]]*]");

	private static final Map<Character, Double> FRACTIONS = Map.of(
			'½', 0.5, '⅓', 1 / 3.0, '⅔', 2 / 3.0, '¼', 0.25, '¾', 0.75, '⅛', 0.125);

	// 대소문자를 구분한다: Ts/T = 큰술, ts/t = 작은술
	private static final Map<String, String> UNIT_ALIASES = Map.ofEntries(
			Map.entry("g", "g"), Map.entry("G", "g"), Map.entry("그램", "g"), Map.entry("kg", "kg"),
			Map.entry("ml", "ml"), Map.entry("mL", "ml"), Map.entry("ML", "ml"), Map.entry("㎖", "ml"),
			Map.entry("cc", "ml"), Map.entry("m", "ml"), Map.entry("L", "L"), Map.entry("l", "L"), Map.entry("ℓ", "L"),
			Map.entry("Ts", "큰술"), Map.entry("T", "큰술"), Map.entry("TS", "큰술"), Map.entry("tbsp", "큰술"),
			Map.entry("큰술", "큰술"), Map.entry("큰스푼", "큰술"), Map.entry("스푼", "큰술"),
			Map.entry("ts", "작은술"), Map.entry("t", "작은술"), Map.entry("tsp", "작은술"),
			Map.entry("작은술", "작은술"), Map.entry("티스푼", "작은술"));

	private IngredientParser() {
	}

	public static List<ParsedIngredient> parse(String text) {
		List<ParsedIngredient> result = new ArrayList<>();
		if (text == null) return result;
		String section = null;
		String normalized = text.replace("\r", "").replaceAll("(?i)<br\\s*/?>", "\n").replace("\"", "")
				// "[소스소개] 고추장 1g", 닫히지 않은 "[소스소개 단촛물:..." → 줄을 바꾼다
				.replaceAll("\\s*\\[\\s*소스소개\\s*]?\\s*", "\n")
				// 줄 앞 "[주재료]현미", "(반죽재료) 강력분" → "주재료:현미"
				.replaceAll("(?m)^[ \\t]*\\[\\s*([^\\]\\d]+?)\\s*]", "$1:")
				.replaceAll("(?m)^[ \\t]*\\(\\s*([^)\\d]*재료)\\s*\\)", "$1:");
		normalized = BRACKET_TAG.matcher(normalized).replaceAll("\n");
		for (String line : normalized.split("\n")) {
			List<String> items = new ArrayList<>();
			for (String item : splitTopLevel(unnest(line))) items.addAll(splitMissingComma(item));
			for (int idx = 0; idx < items.size(); idx++) {
				String t = BULLET.matcher(items.get(idx)).replaceFirst("").trim();
				// 줄 첫 항목 앞의 소제목: "육수 다시마(2.5g)", "톳 무침양념 설탕(2g)"
				if (idx == 0 && topLevelIndexOf(t, ':') < 0) {
					String[] split = splitLeadingLabel(t);
					if (split != null) {
						section = split[0];
						t = split[1];
					}
				}
				// "양념장 : 간장 1g", "밀푀유 재료 > 박력분 65g" → 소제목 + 재료
				int colon = topLevelIndexOf(t.replace('>', ':'), ':');
				if (colon >= 0) {
					String left = t.substring(0, colon);
					// "비트 10 드레싱: ..." → 앞 재료 "비트 10" + 소제목 "드레싱"
					Matcher lm = ITEM_THEN_LABEL.matcher(left);
					if (lm.matches()) {
						result.add(parseItem(section, lm.group(1).trim()));
						left = lm.group(2);
					}
					String label = cleanLabel(left);
					if (!label.isEmpty()) section = label;
					t = BULLET.matcher(t.substring(colon + 1)).replaceFirst("").trim();
				}
				if (t.isEmpty() || SERVING_NOTE.matcher(t).matches()) continue;
				// 수량 없는 단독 줄은 소제목 ("두유 소스", "곁들임")
				if (items.size() == 1 && colon < 0 && !hasDigit(t) && !VAGUE.matcher(t).find()) {
					section = cleanLabel(t);
					continue;
				}
				ParsedIngredient parsed = parseItem(section, t);
				// 원문 오타 "포도씨유, 12g": 이름 없는 수량은 앞의 수량 없는 재료에 붙인다
				boolean amountOnly = parsed.name().isEmpty() || PURE_AMOUNT.matcher(t).matches();
				if (amountOnly && !result.isEmpty() && result.getLast().status() == Status.NO_AMOUNT) {
					parsed = parseItem(section, result.removeLast().raw() + " " + t);
				}
				result.add(parsed);
			}
		}
		return result;
	}

	static ParsedIngredient parseItem(String section, String raw) {
		if (VAGUE.matcher(raw).find() && !hasDigit(stripParens(raw))) {
			String name = clean(VAGUE.matcher(stripParens(raw)).replaceAll(""));
			return new ParsedIngredient(section, raw, name, notes(raw, null), null, null, null, Status.VAGUE);
		}

		// 후보: 괄호 밖 끝의 수량, 괄호 안의 수량. g·ml > 그 밖의 단위 > 단위 없음 순으로 고른다
		Candidate best = null;
		String base = stripParens(raw);
		String name = clean(base);
		Matcher m = TRAILING_AMOUNT.matcher(base);
		if (m.matches() && !m.group(1).isBlank()) {
			name = clean(m.group(1));
			best = Candidate.of(m.group(2), m.group(3), m.group(4));
		}
		List<String> inner = parenContents(raw);
		String usedInner = null;
		for (String part : inner) {
			for (String piece : splitTopLevel(part.replace('=', ','))) {
				Matcher pm = PURE_AMOUNT.matcher(piece);
				if (!pm.matches()) continue;
				Candidate c = Candidate.of(pm.group(1), pm.group(2), pm.group(3));
				if (best == null || c.rank() < best.rank()) {
					best = c;
					usedInner = piece;
				}
			}
		}
		String note = notes(raw, usedInner);
		// 꼬치·나무막대기 같은 조리 도구는 수량을 남기되 영양 계산에서 빠지도록 구분한다
		if (NON_FOOD.matcher(name).find()) {
			return new ParsedIngredient(section, raw, name, note, best == null ? null : best.amount, best == null ? null : best.unit, null, Status.NON_FOOD);
		}
		if (best == null) {
			return new ParsedIngredient(section, raw, name, note, null, null, null, Status.NO_AMOUNT);
		}
		return new ParsedIngredient(section, raw, name, note, best.amount, best.unit, best.grams(), best.status());
	}

	/** 수량만 있는 문자열("3/4모", "1⅓큰술", "30g")의 수량·정규화 단위. 수량이 아니면 null */
	public static Amount amountOf(String text) {
		Matcher m = PURE_AMOUNT.matcher(text);
		if (!m.matches()) return null;
		Candidate c = Candidate.of(m.group(1), m.group(2), m.group(3));
		return new Amount(c.amount, c.unit);
	}

	public record Amount(double amount, String unit) {
	}

	private record Candidate(double amount, String unit) {

		static Candidate of(String from, String to, String rawUnit) {
			double a = toNumber(from);
			if (to != null) a = (a + toNumber(to)) / 2; // 범위는 중간값
			String u = rawUnit == null ? null : UNIT_ALIASES.getOrDefault(rawUnit, rawUnit);
			if ("kg".equals(u)) return new Candidate(a * 1000, "g");
			if ("L".equals(u)) return new Candidate(a * 1000, "ml");
			return new Candidate(a, u);
		}

		Status status() {
			if (unit == null) return Status.ASSUMED_GRAM;
			if (unit.equals("g")) return Status.GRAM;
			if (unit.equals("ml")) return Status.ML;
			return Status.NEEDS_CONVERSION;
		}

		Double grams() {
			return status() == Status.GRAM || status() == Status.ASSUMED_GRAM ? amount : null;
		}

		int rank() {
			return switch (status()) {
				case GRAM, ML -> 0;
				case NEEDS_CONVERSION -> 1;
				default -> 2;
			};
		}
	}

	static double toNumber(String s) {
		s = s.replace(" ", "");
		double frac = 0;
		char last = s.charAt(s.length() - 1);
		if (FRACTIONS.containsKey(last)) {
			frac = FRACTIONS.get(last);
			s = s.substring(0, s.length() - 1);
			if (s.isEmpty()) return frac;
		}
		int slash = s.indexOf('/');
		if (slash > 0) {
			return Double.parseDouble(s.substring(0, slash)) / Double.parseDouble(s.substring(slash + 1)) + frac;
		}
		return Double.parseDouble(s) + frac;
	}

	/**
	 * 재료명 앞부분에서 소제목 단어(…재료, …양념, …소스, …육수 등)로 끝나는 가장 긴 접두어를 떼어낸다.
	 * 마지막 단어는 재료명이어야 하므로 떼지 않는다 ("토마토 소스 30g"은 그대로).
	 */
	static String[] splitLeadingLabel(String t) {
		int end = 0;
		while (end < t.length() && t.charAt(end) != '(' && !Character.isDigit(t.charAt(end))) end++;
		String[] tokens = t.substring(0, end).trim().split("\\s+");
		for (int k = tokens.length - 2; k >= 0; k--) {
			if (LABEL_WORD.matcher(tokens[k]).matches()) {
				String label = String.join(" ", Arrays.copyOfRange(tokens, 0, k + 1));
				int cut = t.indexOf(tokens[k], t.indexOf(tokens[0])) + tokens[k].length();
				return new String[]{label, t.substring(cut).trim()};
			}
		}
		return null;
	}

	static List<String> splitMissingComma(String item) {
		String marked = MISSING_SPACE.matcher(item).replaceAll("$1\u0000");
		marked = MISSING_COMMA.matcher(marked).replaceAll("$1\u0000");
		return List.of(marked.split("\u0000")).stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
	}

	/** "(70g(1토막))" → "(70g, 1토막)" */
	static String unnest(String s) {
		String prev;
		do {
			prev = s;
			s = NESTED.matcher(s).replaceAll("($1, $2$3)");
		} while (!s.equals(prev));
		return s;
	}

	/** 괄호 깊이 0인 쉼표에서만 나눈다: "닭고기(가슴살, 30g), 쌀(50g)" → 2개 */
	static List<String> splitTopLevel(String s) {
		// 괄호 짝이 안 맞는 원문("바지락(모시조개( 200g, ...")은 괄호를 무시하고 쉼표로만 나눈다
		boolean balanced = s.chars().filter(c -> c == '(').count() == s.chars().filter(c -> c == ')').count();
		List<String> out = new ArrayList<>();
		StringBuilder buf = new StringBuilder();
		int depth = 0;
		for (char ch : s.toCharArray()) {
			if (balanced && ch == '(') depth++;
			if (balanced && ch == ')') depth = Math.max(0, depth - 1);
			if (ch == ',' && depth == 0) {
				add(out, buf);
				buf.setLength(0);
			} else {
				buf.append(ch);
			}
		}
		add(out, buf);
		return out;
	}

	private static void add(List<String> out, StringBuilder buf) {
		String v = buf.toString().trim();
		if (!v.isEmpty()) out.add(v);
	}

	private static int topLevelIndexOf(String s, char target) {
		int depth = 0;
		for (int i = 0; i < s.length(); i++) {
			char ch = s.charAt(i);
			if (ch == '(') depth++;
			if (ch == ')') depth = Math.max(0, depth - 1);
			if (ch == target && depth == 0) return i;
		}
		return -1;
	}

	private static String stripParens(String s) {
		return s.replaceAll("\\([^()]*\\)", " ").replaceAll("\\s+", " ").trim();
	}

	private static List<String> parenContents(String s) {
		List<String> out = new ArrayList<>();
		Matcher m = Pattern.compile("\\(([^()]*)\\)").matcher(s);
		while (m.find()) out.add(m.group(1));
		return out;
	}

	private static String notes(String raw, String usedAmount) {
		List<String> parts = new ArrayList<>();
		for (String inner : parenContents(raw)) {
			for (String piece : splitTopLevel(inner.replace('=', ','))) {
				if (!piece.equals(usedAmount) && !VAGUE.matcher(piece).matches()) parts.add(piece);
			}
		}
		return parts.isEmpty() ? null : String.join(", ", parts);
	}

	private static String clean(String s) {
		// 닫히지 않은 괄호 뒤("바지락(모시조개(")는 이름에서 뺀다
		return s.replaceAll("\\([^)]*$", "").replaceAll("\\s+", " ").replaceAll("\\s+각$", "").trim();
	}

	private static String cleanLabel(String s) {
		return clean(BULLET.matcher(s).replaceFirst("").replaceAll("[\\[\\]]", ""));
	}

	private static boolean hasDigit(String s) {
		return s.chars().anyMatch(c -> Character.isDigit(c) || FRACTIONS.containsKey((char) c));
	}
}
