package com.springboot.service;

import com.springboot.domain.NutritionGoal;
import com.springboot.dto.ProfileForm;
import com.springboot.goal.GoalRules;
import com.springboot.goal.GoalRules.Activity;
import com.springboot.goal.GoalRules.DailyReference;
import com.springboot.goal.GoalRules.GoalType;
import com.springboot.goal.GoalRules.Profile;
import com.springboot.goal.GoalRules.Sex;
import com.springboot.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.*;

/** 회원 프로필 → 하루 기준(KDRI), 검진 결과 → 영양 목표 항목. 목표는 회원이 고칠 수 있고, 고친 항목은 다시 만들 때 덮어쓰지 않는다 */
@Service
@RequiredArgsConstructor
@Transactional
public class PersonalGoalService {

	private final JdbcTemplate jdbc;
	private final MemberRepository memberRepository;
	private final HealthCheckupService checkupService;

	public record Goal(Long id, String nutrient, GoalType type, Double min, Double max, int priority, String source, String reason) {
		public String unit() {
			return GoalRules.UNITS.getOrDefault(nutrient, "");
		}

		public Optional<NutritionGoal> preset() {
			return GoalRules.preset(nutrient, type);
		}
	}

	/** 식사 조합·화면에서 쓰는 개인 기준 묶음 */
	public record Personal(Profile profile, DailyReference reference, List<Goal> goals) {
		public Optional<Goal> goal(String nutrient) {
			return goals.stream().filter(g -> g.nutrient().equals(nutrient)).findFirst();
		}

		public Optional<NutritionGoal> primaryPreset() {
			return goals.stream().findFirst().flatMap(Goal::preset);
		}

		public String sex() {
			return profile.sex() == null ? null : profile.sex().name();
		}
	}

	@Transactional(readOnly = true)
	public Optional<ProfileForm> profileForm(String username) {
		List<ProfileForm> rows = jdbc.query("""
						SELECT p.birth_year, p.sex, p.height_cm, p.weight_kg, p.activity, p.excluded_foods
						FROM member_profiles p JOIN users u ON u.id = p.member_id WHERE u.username = ?""",
				(rs, i) -> {
					ProfileForm f = new ProfileForm();
					f.setBirthYear((Integer) rs.getObject(1));
					f.setSex(rs.getString(2));
					f.setHeightCm((Double) rs.getObject(3));
					f.setWeightKg((Double) rs.getObject(4));
					f.setActivity(rs.getString(5));
					f.setExcludedFoods(rs.getString(6));
					return f;
				}, username);
		return rows.stream().findFirst();
	}

	public void saveProfile(String username, ProfileForm f) {
		Long memberId = memberRepository.findByUsername(username).orElseThrow().getId();
		Object[] values = {f.getBirthYear(), blank(f.getSex()), f.getHeightCm(), f.getWeightKg(), blank(f.getActivity()),
				blank(f.getExcludedFoods()), Timestamp.valueOf(LocalDateTime.now()), memberId};
		int updated = jdbc.update("UPDATE member_profiles SET birth_year = ?, sex = ?, height_cm = ?, weight_kg = ?, activity = ?, "
				+ "excluded_foods = ?, updated_at = ? WHERE member_id = ?", values);
		if (updated == 0) {
			jdbc.update("INSERT INTO member_profiles (birth_year, sex, height_cm, weight_kg, activity, excluded_foods, updated_at, member_id) "
					+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", values);
		}
	}

	/** 프로필, 없으면 최근 검진의 성별만 */
	@Transactional(readOnly = true)
	public Profile profile(String username) {
		Optional<ProfileForm> f = profileForm(username);
		String sex = f.map(ProfileForm::getSex).orElse(null);
		if (sex == null) {
			sex = checkupService.list(username).stream().findFirst()
					.flatMap(s -> checkupService.detail(username, s.id())).map(HealthCheckupService.Detail::sex).orElse(null);
		}
		return new Profile(f.map(ProfileForm::getBirthYear).map(y -> Year.now().getValue() - y).orElse(null),
				sex == null ? null : Sex.valueOf(sex), f.map(ProfileForm::getHeightCm).orElse(null), f.map(ProfileForm::getWeightKg).orElse(null),
				f.map(ProfileForm::getActivity).map(Activity::valueOf).orElse(null));
	}

	@Transactional(readOnly = true)
	public Personal personal(String username) {
		Profile p = profile(username);
		return new Personal(p, GoalRules.reference(p), goals(username));
	}

	@Transactional(readOnly = true)
	public List<Goal> goals(String username) {
		return jdbc.query("""
						SELECT g.id, g.nutrient, g.goal_type, g.min_value, g.max_value, g.priority, g.source, g.reason
						FROM nutrition_goal_items g JOIN users u ON u.id = g.member_id WHERE u.username = ? ORDER BY g.priority, g.id""",
				(rs, i) -> new Goal(rs.getLong(1), rs.getString(2), GoalType.valueOf(rs.getString(3)), (Double) rs.getObject(4),
						(Double) rs.getObject(5), rs.getInt(6), rs.getString(7), rs.getString(8)), username);
	}

	/**
	 * 최근 검진 결과와 프로필로 목표를 다시 만든다. 검진으로 만든 항목만 바꾸고, 회원이 고친 영양소는 그대로 둔다.
	 * 반환: 새로 만든 항목 수 (검진이 없으면 0)
	 */
	public int regenerate(String username) {
		Long memberId = memberRepository.findByUsername(username).orElseThrow().getId();
		var latest = checkupService.list(username).stream().findFirst().flatMap(s -> checkupService.detail(username, s.id()));
		jdbc.update("DELETE FROM nutrition_goal_items WHERE member_id = ? AND source = 'CHECKUP'", memberId);
		if (latest.isEmpty()) return 0;
		Set<String> userSet = new HashSet<>(jdbc.queryForList("SELECT nutrient FROM nutrition_goal_items WHERE member_id = ?", String.class, memberId));
		int added = 0;
		for (GoalRules.Item i : GoalRules.fromCheckup(latest.get().evaluation(), profile(username))) {
			if (userSet.contains(i.nutrient())) continue;
			insert(memberId, i.nutrient(), i.type(), i.min(), i.max(), 100 + i.priority(), "CHECKUP", i.reason());
			added++;
		}
		renumber(memberId);
		return added;
	}

	/** 직접 추가하거나 고친다 (같은 영양소면 고침). 오류 문구 또는 null */
	public String saveGoal(String username, String nutrient, GoalType type, Double min, Double max, int priority) {
		if (!GoalRules.UNITS.containsKey(nutrient)) return "목표로 정할 수 없는 영양소입니다.";
		if ((type != GoalType.MIN && (max == null || max <= 0)) || (type != GoalType.MAX && (min == null || min <= 0))) return "값을 입력하세요.";
		if (type == GoalType.RANGE && min >= max) return "범위의 아래값이 위값보다 작아야 합니다.";
		Long memberId = memberRepository.findByUsername(username).orElseThrow().getId();
		jdbc.update("DELETE FROM nutrition_goal_items WHERE member_id = ? AND nutrient = ?", memberId, nutrient);
		// 고른 순위 앞에 끼워 넣는다
		insert(memberId, nutrient, type, type == GoalType.MAX ? null : min, type == GoalType.MIN ? null : max, Math.max(1, priority) * 10 - 5,
				"USER", "직접 정한 목표");
		renumber(memberId);
		return null;
	}

	public boolean deleteGoal(String username, Long id) {
		Long memberId = memberRepository.findByUsername(username).orElseThrow().getId();
		boolean ok = jdbc.update("DELETE FROM nutrition_goal_items WHERE id = ? AND member_id = ?", id, memberId) > 0;
		renumber(memberId);
		return ok;
	}

	private void insert(Long memberId, String nutrient, GoalType type, Double min, Double max, int priority, String source, String reason) {
		jdbc.update("INSERT INTO nutrition_goal_items (member_id, nutrient, goal_type, min_value, max_value, priority, source, reason, updated_at) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", memberId, nutrient, type.name(), min, max, priority, source, reason, Timestamp.valueOf(LocalDateTime.now()));
	}

	/** 순위를 1, 2, 3…으로 다시 매긴다 (×10 해 두면 사이에 끼워 넣기 쉽다) */
	private void renumber(Long memberId) {
		List<Long> ids = jdbc.queryForList("SELECT id FROM nutrition_goal_items WHERE member_id = ? ORDER BY priority, id", Long.class, memberId);
		for (int i = 0; i < ids.size(); i++) jdbc.update("UPDATE nutrition_goal_items SET priority = ? WHERE id = ?", (i + 1) * 10, ids.get(i));
	}

	private static String blank(String s) {
		return s == null || s.isBlank() ? null : s.trim();
	}
}
