package com.springboot.checkup;

import com.springboot.domain.NutritionGoal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 검진 수치 → 판정과 영양 목표 추천. 기준값은 checkup-rules.csv에 출처와 함께 두고 코드에 적지 않는다.
 * 진단이 아니라 서비스 안의 추천 범위를 정하는 용도다.
 */
public final class CheckupRules {

	public enum Judgment { NORMAL, BORDERLINE, HIGH, LOW }

	/** 서비스 상태 (진단명이 아님) */
	public enum ServiceStatus { NORMAL, MANAGEMENT_REQUIRED, MEDICAL_CONSULTATION_RECOMMENDED, INSUFFICIENT_DATA }

	public enum Sex { M, F }

	record Rule(TestItem item, String operator, double threshold, Sex sex, Judgment judgment, NutritionGoal goal, boolean medical, String message) {

		boolean matches(double v, Sex who) {
			if (sex != null && sex != who) return false;
			return switch (operator) {
				case ">=" -> v >= threshold;
				case ">" -> v > threshold;
				case "<" -> v < threshold;
				case "<=" -> v <= threshold;
				default -> throw new IllegalStateException("알 수 없는 비교: " + operator);
			};
		}
	}

	public record ItemJudgment(TestItem item, Double value, Judgment judgment, String message, boolean medical) {
	}

	public record Evaluation(List<ItemJudgment> items, ServiceStatus status, List<NutritionGoal> recommendedGoals, boolean kidneyCaution) {

		public Optional<NutritionGoal> primaryGoal() {
			return recommendedGoals.stream().findFirst();
		}
	}

	/** 목표 우선순위: 혈압 관련 저나트륨을 먼저 */
	private static final List<NutritionGoal> PRIORITY = List.of(NutritionGoal.LOW_SODIUM, NutritionGoal.LOW_CALORIE, NutritionGoal.HIGH_PROTEIN);

	private final List<Rule> rules;

	private CheckupRules(List<Rule> rules) {
		this.rules = rules;
	}

	public static CheckupRules load() {
		try (BufferedReader r = new BufferedReader(new InputStreamReader(
				Objects.requireNonNull(CheckupRules.class.getResourceAsStream("/checkup-rules.csv")), StandardCharsets.UTF_8))) {
			List<Rule> rules = new ArrayList<>();
			for (String line : r.lines().toList()) {
				if (line.isBlank() || line.startsWith("#")) continue;
				String[] c = line.split(",", -1);
				rules.add(new Rule(TestItem.valueOf(c[0]), c[1], Double.parseDouble(c[2]), c[3].isBlank() ? null : Sex.valueOf(c[3]),
						Judgment.valueOf(c[4]), c[5].isBlank() ? null : NutritionGoal.valueOf(c[5]), "Y".equals(c[6]), c[7]));
			}
			return new CheckupRules(rules);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public Evaluation evaluate(Map<TestItem, Double> values, Sex sex) {
		List<ItemJudgment> items = new ArrayList<>();
		Set<NutritionGoal> goals = new HashSet<>();
		boolean medical = false, kidney = false, any = false;
		for (TestItem item : TestItem.values()) {
			Double v = values.get(item);
			if (v == null) continue; // 검사하지 않았거나 추출하지 못한 항목은 정상으로 보지 않고 판정에서 뺀다
			any = true;
			Rule hit = rules.stream().filter(r -> r.item() == item && r.matches(v, sex)).findFirst().orElse(null);
			if (hit == null) {
				items.add(new ItemJudgment(item, v, Judgment.NORMAL, "정상 범위", false));
				continue;
			}
			items.add(new ItemJudgment(item, v, hit.judgment(), hit.message(), hit.medical()));
			if (hit.goal() != null) goals.add(hit.goal());
			medical |= hit.medical();
			kidney |= item == TestItem.EGFR || item == TestItem.CREATININE;
		}
		if (kidney) goals.remove(NutritionGoal.HIGH_PROTEIN); // 신장 기능 이상이면 단백질을 늘리는 추천은 하지 않는다

		ServiceStatus status = !any ? ServiceStatus.INSUFFICIENT_DATA
				: medical ? ServiceStatus.MEDICAL_CONSULTATION_RECOMMENDED
				: items.stream().anyMatch(i -> i.judgment() != Judgment.NORMAL) ? ServiceStatus.MANAGEMENT_REQUIRED
				: ServiceStatus.NORMAL;
		List<NutritionGoal> ordered = PRIORITY.stream().filter(goals::contains).toList();
		return new Evaluation(items, status, ordered, kidney);
	}
}
