package com.springboot.repository;

import com.springboot.domain.NutritionGoal;
import com.springboot.domain.SavedRecipe;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 맞춤 레시피 저장소: 회원별 목록, 본인 것만 찾기 */
public interface SavedRecipeRepository extends JpaRepository<SavedRecipe, Long> {

	List<SavedRecipe> findByMemberUsernameOrderBySavedAtDesc(String username);

	Optional<SavedRecipe> findByIdAndMemberUsername(Long id, String username);

	Optional<SavedRecipe> findByMemberUsernameAndRecipeSeqAndGoalAndOptionId(String username, String recipeSeq, NutritionGoal goal, String optionId);
}
