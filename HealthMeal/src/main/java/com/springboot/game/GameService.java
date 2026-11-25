package com.springboot.game;

import com.springboot.i18n.Translator;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 영양 퀴즈 게임(브라우저에서 실행, static/js/nutrition-game.js)에 문제 재료를 준다.
 * 식사 조합 후보(meal_items: 편의점·외식·직접 조리)의 1회 제공량 영양값을 쓴다. 게임 진행·채점은 모두 브라우저에서 한다.
 */
@Service
@RequiredArgsConstructor
public class GameService {

	/** 한 번에 보내는 품목 수: 한 판(15문제 × 2개)을 난이도별로 골라 내기에 충분한 수 */
	static final int DECK_SIZE = 300;

	/** 겨루는 영양소. daily는 식품 표시용 1일 영양성분 기준치(식약처), goal은 이어 볼 식사 조합 목표 */
	public enum Nutrient {
		SODIUM("sodium", "mg", 2000, "LOW_SODIUM"),
		SUGAR("sugar", "g", 100, "LOW_SUGAR"),
		ENERGY("energy", "kcal", 2000, "LOW_CALORIE"),
		SAT_FAT("saturated_fat", "g", 15, "LOW_SAT_FAT"),
		FIBER("fiber", "g", 25, "HIGH_FIBER"),
		PROTEIN("protein", "g", 55, "HIGH_PROTEIN");

		final String column;
		public final String unit;
		public final double daily;
		public final String goal;

		Nutrient(String column, String unit, double daily, String goal) {
			this.column = column;
			this.unit = unit;
			this.daily = daily;
			this.goal = goal;
		}
	}

	/** 문제 재료 한 개. maker는 영어 화면에서 비운다 (제조사 이름은 번역하지 않음) */
	public record Food(long id, String name, String maker, String situation, Double servingGrams, double value) {}

	public record Deck(String nutrient, String unit, double daily, String goal, List<Food> foods) {}

	private final JdbcTemplate jdbc;
	private final Translator translator;

	public Deck deck(Nutrient n) {
		// 열 이름은 enum에서만 온다 (요청 값이 SQL에 들어가지 않음)
		List<Food> rows = jdbc.query("SELECT id, name, maker, situation, serving_grams, " + n.column
						+ " FROM meal_items WHERE " + n.column + " > 0 ORDER BY RAND() LIMIT " + (DECK_SIZE * 2),
				(rs, i) -> new Food(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
						(Double) rs.getObject(5), rs.getDouble(6)));
		boolean english = translator.english();
		Set<String> seen = new HashSet<>();
		List<Food> out = new ArrayList<>();
		for (Food f : rows) {
			String name = english ? translator.en(f.name()) : f.name();
			if (name == null || !seen.add(name)) continue; // 영어 화면은 번역이 있는 품목만, 같은 이름은 한 번만
			out.add(new Food(f.id(), name, english ? null : f.maker(), f.situation(), f.servingGrams(), round(f.value())));
			if (out.size() == DECK_SIZE) break;
		}
		return new Deck(n.name(), n.unit, n.daily, n.goal, out);
	}

	static double round(double v) {
		return Math.round(v * 10) / 10.0;
	}
}
