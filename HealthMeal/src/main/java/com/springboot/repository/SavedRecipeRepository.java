package com.springboot.repository;

import com.springboot.domain.NutritionGoal;
import com.springboot.domain.SavedRecipe;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SavedRecipeRepository extends JpaRepository<SavedRecipe, Long> {

	List<SavedRecipe> findByMemberEmailOrderBySavedAtDesc(String email);

	Optional<SavedRecipe> findByIdAndMemberEmail(Long id, String email);

	Optional<SavedRecipe> findByMemberEmailAndRecipeSeqAndGoalAndOptionId(String email, String recipeSeq, NutritionGoal goal, String optionId);
}
