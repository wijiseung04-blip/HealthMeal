package com.springboot.nutrition;

import com.springboot.nutrition.IngredientRoleClassifier.Classification;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 레시피 재료 한 줄의 역할 2축 (기획서 7-2): 조리 역할과 영양 역할. 규칙으로 정하며 AI를 쓰지 않는다.
 * 조리 역할은 기획서 목록에 "조리 매체"(볶음·튀김 기름)와 "주재료"를 더했다.
 */
public final class IngredientAnalysis {

	public enum CulinaryRole {
		FLAVOR("풍미"), EMULSIFY("유화"), THICKEN("점도"), BIND("결착"), MOISTURE("수분 공급"), STRUCTURE("구조 형성"),
		SEASONING("염분·조미"), COOKING_MEDIUM("조리 매체"), MAIN("주재료"), OTHER("기타");

		private final String label;

		CulinaryRole(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}
	}

	public enum NutritionRole {
		PROTEIN("단백질"), FAT("지방"), CARBOHYDRATE("탄수화물"), FIBER("식이섬유"), SODIUM("나트륨 공급"), OTHER("기타");

		private final String label;

		NutritionRole(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}
	}

	public record Result(CulinaryRole culinary, NutritionRole nutrition, String evidence) {
	}

	private static final Pattern FLAVOR_SEASONING = Pattern.compile("식초|후추|후춧|고추|겨자|와사비|카레|허브|바질|파슬리|계피|생강|마늘|레몬|맛술|청주|정종|와인|향신|깨");
	private static final Pattern EMULSIFIER = Pattern.compile("마요네즈|드레싱");
	private static final Pattern BINDER_CUE = Pattern.compile("반죽|튀김옷|묻혀|묻히|입혀|입히|달걀물|계란물|빵가루");
	private static final Pattern FLOUR = Pattern.compile("밀가루|부침가루|튀김가루|빵가루|박력분|강력분|중력분|전분|녹말");

	private IngredientAnalysis() {
	}

	/**
	 * per100g: 그 식품의 100g당 단백질·지방·탄수화물·나트륨·총 식이섬유 (없으면 빈 맵). steps: 레시피 조리 순서
	 */
	public static Result analyze(String name, String foodCode, String foodGroup, Double grams, Map<String, Double> per100g, List<String> steps) {
		String g = foodGroup == null ? "" : foodGroup;
		String n = name == null ? "" : name.replaceAll("\\s+", "");
		return new Result(culinary(n, foodCode, g, grams, steps), nutrition(foodCode, g, per100g), evidence(n, steps));
	}

	static CulinaryRole culinary(String n, String code, String g, Double grams, List<String> steps) {
		if ("WATER".equals(code) || n.endsWith("물") && n.length() <= 2 || n.contains("육수")) return CulinaryRole.MOISTURE;
		if (EMULSIFIER.matcher(n).find()) return CulinaryRole.EMULSIFY;
		if (IngredientRoleClassifier.applies(g)) {
			Classification c = IngredientRoleClassifier.classify(n, g, steps);
			return switch (c.role()) {
				case COOKING_MEDIUM, DEEP_FRY -> CulinaryRole.COOKING_MEDIUM;
				case FLAVOR -> CulinaryRole.FLAVOR;
				case STRUCTURE -> CulinaryRole.STRUCTURE;
				case UNKNOWN -> CulinaryRole.OTHER;
			};
		}
		if (FLOUR.matcher(n).find() || g.contains("전분")) {
			if (n.contains("전분") || n.contains("녹말")) return near(n, steps) ? CulinaryRole.BIND : CulinaryRole.THICKEN;
			return near(n, steps) ? CulinaryRole.BIND : CulinaryRole.STRUCTURE;
		}
		if (g.contains("조미료") || g.contains("장류")) return FLAVOR_SEASONING.matcher(n).find() ? CulinaryRole.FLAVOR : CulinaryRole.SEASONING;
		if (g.contains("난류") && near(n, steps)) return CulinaryRole.BIND;
		if ((g.contains("우유") || g.contains("유제품")) && n.contains("크림")) return CulinaryRole.EMULSIFY;
		if (g.contains("견과") || g.contains("종실")) return CulinaryRole.FLAVOR;
		if (g.contains("육류") || g.contains("어패류") || g.contains("난류") || g.contains("두류") || g.contains("곡류") || g.contains("감자")
				|| g.contains("채소") || g.contains("버섯") || g.contains("해조") || g.contains("과일") || g.contains("우유") || g.contains("유제품")) {
			return CulinaryRole.MAIN;
		}
		return CulinaryRole.OTHER;
	}

	/** 100g당 성분으로: 나트륨이 많으면 나트륨 공급, 지방이 절반 넘으면 지방, 열량 중 단백질 비중이 크면 단백질 … */
	static NutritionRole nutrition(String code, String g, Map<String, Double> v) {
		if (code == null || "WATER".equals(code)) return NutritionRole.OTHER;
		double protein = value(v, "단백질"), fat = value(v, "지방"), carb = value(v, "탄수화물"), sodium = value(v, "나트륨"), fiber = value(v, "총 식이섬유");
		double kcal = protein * 4 + fat * 9 + carb * 4;
		if (sodium >= 1000) return NutritionRole.SODIUM;
		if (fat >= 50) return NutritionRole.FAT;
		if (kcal > 0 && protein * 4 / kcal >= 0.3 && protein >= 5) return NutritionRole.PROTEIN;
		if (carb >= 20) return NutritionRole.CARBOHYDRATE;
		if (fiber >= 2 || g.contains("채소") || g.contains("버섯") || g.contains("해조") || g.contains("과일")) return NutritionRole.FIBER;
		if (kcal > 0 && fat * 9 / kcal >= 0.5) return NutritionRole.FAT;
		return NutritionRole.OTHER;
	}

	/** 성분 값이 없거나 null이면 0 */
	private static double value(Map<String, Double> v, String nutrient) {
		Double d = v.get(nutrient);
		return d == null ? 0 : d;
	}

	/** 재료 바로 뒤(약 14자)에 반죽·튀김옷 등 결착 단서가 있는지 */
	private static boolean near(String n, List<String> steps) {
		for (String s : IngredientRoleClassifier.sentences(steps)) {
			String c = s.replaceAll("\\s+", "");
			int i = c.indexOf(n);
			if (i >= 0 && BINDER_CUE.matcher(c.substring(i, Math.min(c.length(), i + n.length() + 14))).find()) return true;
		}
		return false;
	}

	/** 근거: 재료가 나오는 첫 조리 문장 */
	private static String evidence(String n, List<String> steps) {
		if (n.isEmpty()) return null;
		for (String s : IngredientRoleClassifier.sentences(steps)) {
			if (s.replaceAll("\\s+", "").contains(n)) {
				String t = s.trim();
				return t.length() > 200 ? t.substring(0, 199) + "…" : t;
			}
		}
		return null;
	}
}
