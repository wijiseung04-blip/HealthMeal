package com.springboot.service;

import com.springboot.domain.NutritionGoal;
import com.springboot.domain.Recipe;
import com.springboot.domain.SavedRecipe;
import com.springboot.repository.MemberRepository;
import com.springboot.repository.RecipeRepository;
import com.springboot.repository.SavedRecipeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** 회원의 맞춤 레시피 저장·목록·삭제 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SavedRecipeService {

	private final SavedRecipeRepository savedRepository;
	private final RecipeRepository recipeRepository;
	private final MemberRepository memberRepository;
	private final SubstitutionService substitutionService;

	/** 목록 행. recipeId는 지금 DB의 레시피 id (레시피가 없어졌으면 null) */
	public record Row(Long id, Long recipeId, String recipeName, NutritionGoal goal, String optionId, String ingredient,
					  String description, Double delta, Double rate, String rateKind, String verdict, LocalDateTime savedAt) {
	}

	/** 계산을 다시 해서 후보가 실제로 있을 때만 저장한다. 같은 것을 다시 저장하면 시각만 갱신 */
	@Transactional
	public Optional<Long> save(String username, Long recipeId, NutritionGoal goal, String optionId) {
		Optional<Recipe> recipe = recipeRepository.findById(recipeId);
		if (recipe.isEmpty()) return Optional.empty();
		var option = substitutionService.suggest(recipeId, goal)
				.flatMap(s -> s.options().stream().filter(o -> o.id().equals(optionId)).findFirst());
		if (option.isEmpty()) return Optional.empty();

		String seq = recipe.get().getSeq();
		Optional<SavedRecipe> existing = savedRepository.findByMemberUsernameAndRecipeSeqAndGoalAndOptionId(username, seq, goal, optionId);
		if (existing.isPresent()) {
			existing.get().touch();
			return Optional.of(existing.get().getId());
		}
		var o = option.get();
		var c = o.goalComparison();
		SavedRecipe saved = savedRepository.save(new SavedRecipe(memberRepository.findByUsername(username).orElseThrow(), seq,
				recipe.get().getName(), goal, optionId, o.type().name(), cut(o.ingredient(), 500), cut(o.description(), 300), o.gramsBefore(), o.gramsAfter(),
				c.before(), c.after(), c.delta(), c.rate(), c.rateKind().name(), o.verdict().name()));
		log.info("맞춤 레시피 저장: savedId={}, recipeSeq={}, goal={}", saved.getId(), seq, goal);
		return Optional.of(saved.getId());
	}

	/** 묶음 후보는 설명이 길 수 있다 */
	private static String cut(String v, int max) {
		return v == null || v.length() <= max ? v : v.substring(0, max - 1) + "…";
	}

	public List<Row> list(String username) {
		List<SavedRecipe> saved = savedRepository.findByMemberUsernameOrderBySavedAtDesc(username);
		Map<String, Long> ids = new java.util.HashMap<>();
		if (!saved.isEmpty()) {
			recipeRepository.findIdsBySeqs(saved.stream().map(SavedRecipe::getRecipeSeq).collect(Collectors.toSet()))
					.forEach(r -> ids.put((String) r[0], (Long) r[1]));
		}
		return saved.stream().map(s -> new Row(s.getId(), ids.get(s.getRecipeSeq()), s.getRecipeName(), s.getGoal(), s.getOptionId(),
				s.getIngredient(), s.getDescription(), s.getDelta(), s.getRate(), s.getRateKind(), s.getVerdict(), s.getSavedAt())).toList();
	}

	@Transactional
	public boolean delete(String username, Long id) {
		Optional<SavedRecipe> s = savedRepository.findByIdAndMemberUsername(id, username);
		s.ifPresent(savedRepository::delete);
		return s.isPresent();
	}
}
