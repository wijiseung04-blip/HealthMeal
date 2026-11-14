package com.springboot.pipeline;

import com.springboot.pipeline.FoodMatcher.Match;
import com.springboot.pipeline.ParsedIngredient.Status;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.*;

/**
 * 파싱 → g 확정(원문·환산) → 식품 매칭을 한 번에 한다. DB 적재와 오프라인 검증이 같은 결과를 쓰도록 공유한다.
 */
final class IngredientResolver {

	enum GramsSource { RAW, ASSUMED, CONVERTED, AI_ESTIMATED, NONE }

	record Resolved(int position, ParsedIngredient parsed, Double grams, GramsSource gramsSource, Match match) {
	}

	record RecipeData(JsonNode raw, List<Resolved> ingredients) {
	}

	private final FoodMatcher matcher;
	private final UnitConversions conversions;
	private final Map<String, Match> cache = new HashMap<>();

	IngredientResolver(FoodMatcher matcher, UnitConversions conversions) {
		this.matcher = matcher;
		this.conversions = conversions;
	}

	/** 원본 레시피·식품·수동 매핑을 읽어 전체 레시피를 해석한다 */
	static List<RecipeData> resolveAll(List<FoodRecord> foods) throws IOException {
		List<JsonNode> rows = RecipeRaw.load(Collector.RAW.resolve("cookrcp01"));
		Map<JsonNode, List<ParsedIngredient>> parsed = new LinkedHashMap<>();
		List<ParsedIngredient> all = new ArrayList<>();
		for (JsonNode r : rows) {
			List<ParsedIngredient> items = IngredientParser.parse(r.path("RCP_PARTS_DTLS").asString(""));
			parsed.put(r, items);
			all.addAll(items);
		}
		FoodMatcher matcher = new FoodMatcher(foods, IngredientMatching.readManual());
		UnitConversions conversions = UnitConversions.derive(all);
		IngredientResolver resolver = new IngredientResolver(matcher, conversions);
		Map<String, Match> matches = new HashMap<>();
		all.stream().map(ParsedIngredient::name).filter(n -> !n.isBlank()).distinct()
				.forEach(n -> matches.put(n, resolver.cache.computeIfAbsent(n, matcher::match)));
		IngredientMatching.configure(conversions, matches);
		List<RecipeData> out = new ArrayList<>();
		parsed.forEach((r, items) -> out.add(new RecipeData(r, resolver.resolve(items))));
		return out;
	}

	List<Resolved> resolve(List<ParsedIngredient> items) {
		List<Resolved> out = new ArrayList<>();
		for (int i = 0; i < items.size(); i++) {
			ParsedIngredient p = items.get(i);
			if (p.status() == Status.NON_FOOD) {
				out.add(new Resolved(i + 1, p, null, GramsSource.NONE, null));
				continue;
			}
			Double grams = conversions.toGrams(p);
			GramsSource source = grams == null ? GramsSource.NONE
					: p.status() == Status.GRAM ? GramsSource.RAW
					: p.status() == Status.ASSUMED_GRAM ? GramsSource.ASSUMED
					: conversions.aiEstimated(p) ? GramsSource.AI_ESTIMATED : GramsSource.CONVERTED;
			Match match = p.name().isBlank() ? null : cache.computeIfAbsent(p.name(), matcher::match);
			out.add(new Resolved(i + 1, p, grams, source, match));
		}
		return out;
	}
}
