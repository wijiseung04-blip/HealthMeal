package com.springboot.goal;

import com.springboot.checkup.CheckupRules.Evaluation;
import com.springboot.checkup.CheckupRules.Judgment;
import com.springboot.checkup.TestItem;
import com.springboot.domain.NutritionGoal;

import java.util.*;

/**
 * 개인 하루 기준(KDRI)과 검진 판정 → 영양 목표 항목. DB·웹과 분리된 순수 계산이며 진단이 아니다.
 * - 열량: 2020 한국인 영양소 섭취기준 성인 에너지필요추정량 공식 (2025 개정판과 대조 필요)
 * - 프로필(나이·성별·키·몸무게·활동량)이 없으면 열량과 열량 비율로 정하는 목표는 만들지 않는다
 */
public final class GoalRules {

	public enum Sex { M, F }

	/** 신체활동 수준과 성별별 활동계수 (KDRI 에너지필요추정량) */
	public enum Activity {
		SEDENTARY("비활동적 (대부분 앉아서 생활)", 1.0, 1.0),
		LOW_ACTIVE("저활동적 (가벼운 활동)", 1.11, 1.12),
		ACTIVE("활동적 (규칙적인 운동)", 1.25, 1.27),
		VERY_ACTIVE("매우 활동적 (고강도 운동·육체노동)", 1.48, 1.45);

		private final String label;
		private final double male, female;

		Activity(String label, double male, double female) {
			this.label = label;
			this.male = male;
			this.female = female;
		}

		public String label() {
			return label;
		}

		double coefficient(Sex sex) {
			return sex == Sex.M ? male : female;
		}
	}

	public enum GoalType { MAX, MIN, RANGE }

	public record Profile(Integer age, Sex sex, Double heightCm, Double weightKg, Activity activity) {
		public boolean complete() {
			return age != null && sex != null && heightCm != null && weightKg != null && activity != null;
		}
	}

	/** 하루 기준. 프로필이 없으면 energy는 null */
	public record DailyReference(Double energy, Double proteinMin, double sodiumMax, Double carbMin, Double carbMax,
								 Double sugarMax, Double saturatedFatMax, boolean personalized) {
	}

	/** 목표 한 항목 (하루 기준). priority 1이 주목표 */
	public record Item(String nutrient, GoalType type, Double min, Double max, int priority, String reason) {
	}

	public static final String ENERGY = "에너지", SODIUM = "나트륨", PROTEIN = "단백질", CARB = "탄수화물", SUGAR = "당류",
			SAT_FAT = "총 포화 지방산";
	public static final Map<String, String> UNITS = Map.of(ENERGY, "kcal", SODIUM, "mg", PROTEIN, "g", CARB, "g", SUGAR, "g", SAT_FAT, "g");

	/** 만성질환위험감소섭취량(mg/일) */
	static final double SODIUM_LIMIT = 2300;
	/** 혈압이 기준을 넘으면 더 낮게 */
	static final double SODIUM_BP = 2000;

	private GoalRules() {
	}

	/** 성인 에너지필요추정량(kcal/일) */
	public static Double energy(Profile p) {
		if (!p.complete()) return null;
		double h = p.heightCm() / 100, w = p.weightKg(), pa = p.activity().coefficient(p.sex());
		double eer = p.sex() == Sex.M
				? 662 - 9.53 * p.age() + pa * (15.91 * w + 539.6 * h)
				: 354 - 6.91 * p.age() + pa * (9.36 * w + 726 * h);
		return round(eer, 10);
	}

	public static DailyReference reference(Profile p) {
		Double e = energy(p);
		Double protein = p.weightKg() != null ? round(p.weightKg() * 0.91, 1) : p.sex() == Sex.M ? 65.0 : p.sex() == Sex.F ? 55.0 : null;
		return new DailyReference(e, protein, SODIUM_LIMIT,
				e == null ? null : round(e * 0.55 / 4, 1), e == null ? null : round(e * 0.65 / 4, 1),
				e == null ? null : round(e * 0.20 / 4, 1), e == null ? null : round(e * 0.07 / 9, 1), e != null);
	}

	/**
	 * 검진 판정으로 목표 항목을 만든다. 우선순위: 혈압(나트륨) → 중성지방·비만(열량) → 혈당(탄수화물) → LDL(포화지방).
	 * 같은 영양소는 더 엄격한 값 하나만. 신장 기능 이상이면 단백질을 늘리는 목표는 만들지 않는다
	 */
	public static List<Item> fromCheckup(Evaluation e, Profile p) {
		Map<TestItem, Judgment> j = new EnumMap<>(TestItem.class);
		e.items().forEach(i -> j.put(i.item(), i.judgment()));
		DailyReference ref = reference(p);
		List<Item> out = new ArrayList<>();
		if (abnormal(j, TestItem.SYSTOLIC_BP) || abnormal(j, TestItem.DIASTOLIC_BP)) {
			out.add(new Item(SODIUM, GoalType.MAX, null, SODIUM_BP, 0, "혈압이 기준 이상이라 나트륨을 하루 2,000mg 이하로"));
		}
		boolean weight = abnormal(j, TestItem.BMI) || abnormal(j, TestItem.WAIST);
		boolean tg = abnormal(j, TestItem.TRIGLYCERIDE);
		if ((weight || tg) && ref.energy() != null) {
			double floor = p.sex() == Sex.M ? 1500 : 1200;
			double max = weight ? Math.max(floor, ref.energy() - 500) : ref.energy();
			out.add(new Item(ENERGY, GoalType.MAX, null, round(max, 10),
					0, weight ? "체질량지수·허리둘레가 기준 이상이라 필요량보다 하루 500kcal 적게" : "중성지방이 기준 이상이라 필요량을 넘지 않게"));
			if (tg) out.add(new Item(SUGAR, GoalType.MAX, null, round(ref.energy() * 0.10 / 4, 1), 0, "중성지방이 기준 이상이라 당류를 열량의 10% 이하로"));
		}
		if (abnormal(j, TestItem.FASTING_GLUCOSE) && ref.energy() != null) {
			out.add(new Item(CARB, GoalType.RANGE, round(ref.energy() * 0.50 / 4, 1), round(ref.energy() * 0.60 / 4, 1), 0,
					"공복혈당이 기준 이상이라 탄수화물을 열량의 50~60%로"));
			out.add(new Item(SUGAR, GoalType.MAX, null, round(ref.energy() * 0.10 / 4, 1), 0, "공복혈당이 기준 이상이라 당류를 열량의 10% 이하로"));
		}
		if ((abnormal(j, TestItem.LDL) || abnormal(j, TestItem.TOTAL_CHOLESTEROL)) && ref.energy() != null) {
			out.add(new Item(SAT_FAT, GoalType.MAX, null, ref.saturatedFatMax(), 0, "콜레스테롤이 기준 이상이라 포화지방을 열량의 7% 미만으로"));
		}
		if (weight && !e.kidneyCaution() && p.weightKg() != null) {
			out.add(new Item(PROTEIN, GoalType.MIN, round(p.weightKg() * 1.0, 1), null, 0, "체중 관리 중 근육 유지를 위해 단백질을 체중 1kg당 1g 이상"));
		}
		// 같은 영양소는 더 엄격한 것(상한은 작은 값) 하나, 순서대로 우선순위
		Map<String, Item> merged = new LinkedHashMap<>();
		for (Item i : out) merged.merge(i.nutrient(), i, (a, b) -> a.max() != null && b.max() != null && b.max() < a.max() ? b : a);
		List<Item> ordered = new ArrayList<>();
		int priority = 1;
		for (Item i : merged.values()) ordered.add(new Item(i.nutrient(), i.type(), i.min(), i.max(), priority++, i.reason()));
		return ordered;
	}

	/** 주목표 → 감량·대체·식사 조합에서 쓰는 목표 프리셋 */
	public static Optional<NutritionGoal> preset(String nutrient, GoalType type) {
		if (nutrient.equals(SODIUM) && type == GoalType.MAX) return Optional.of(NutritionGoal.LOW_SODIUM);
		if (nutrient.equals(PROTEIN) && type == GoalType.MIN) return Optional.of(NutritionGoal.HIGH_PROTEIN);
		if (Set.of(ENERGY, CARB, SUGAR, SAT_FAT).contains(nutrient)) return Optional.of(NutritionGoal.LOW_CALORIE);
		return Optional.empty();
	}

	private static boolean abnormal(Map<TestItem, Judgment> j, TestItem item) {
		Judgment v = j.get(item);
		return v != null && v != Judgment.NORMAL;
	}

	private static double round(double v, double unit) {
		return Math.round(v / unit) * unit;
	}
}
