package com.springboot.controller;

import com.springboot.domain.NutritionGoal;
import com.springboot.service.RecipeService;
import com.springboot.service.RecipeExportService;
import com.springboot.service.SavedRecipeService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.security.Principal;
import com.springboot.service.SubstitutionService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

/** 레시피 검색·상세 화면 (비회원도 조회 가능). 매개변수 이름은 이클립스 빌드를 위해 모두 명시한다 */
@Controller
@RequestMapping("/recipes")
@RequiredArgsConstructor
public class RecipeController {

	private final RecipeService recipeService;
	private final SubstitutionService substitutionService;
	private final RecipeExportService exportService;
	private final SavedRecipeService savedRecipeService;
	private final com.springboot.service.MyRecipeService myRecipeService;

	/** 개인 레시피는 주인만: 다른 사람에게는 없는 레시피처럼 404 */
	private void checkVisible(Long id, Principal principal) {
		if (!myRecipeService.canView(id, principal == null ? null : principal.getName())) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "레시피를 찾을 수 없습니다.");
		}
	}

	@GetMapping
	public String list(@RequestParam(name = "q", required = false) String q,
					   @RequestParam(name = "category", required = false) String category,
					   @RequestParam(name = "page", defaultValue = "0") int page,
					   @RequestParam(name = "goal", required = false) NutritionGoal goal,
					   Model model) {
		model.addAttribute("goal", goal);
		model.addAttribute("recipes", recipeService.search(q, category, page));
		model.addAttribute("categories", recipeService.categories());
		model.addAttribute("q", q);
		model.addAttribute("category", category);
		return "recipes/list";
	}

	/** 영양 목표에 맞춘 감량·대체 후보와 선택한 후보의 전후 비교 */
	@GetMapping("/{id}/adjust")
	public String adjust(@PathVariable("id") Long id,
						 @RequestParam(name = "goal", defaultValue = "LOW_SODIUM") NutritionGoal goal,
						 @RequestParam(name = "option", required = false) String option,
						 Principal principal, Model model) {
		checkVisible(id, principal);
		model.addAttribute("suggestions", substitutionService.suggest(id, goal)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "레시피를 찾을 수 없습니다.")));
		model.addAttribute("goals", NutritionGoal.values());
		model.addAttribute("recipeId", id);
		if (option != null) {
			model.addAttribute("applied", substitutionService.apply(id, goal, option)
					.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "선택한 후보를 찾을 수 없습니다.")));
		}
		return "recipes/adjust";
	}

	/** 회원 전용(보안 설정상 POST는 로그인 필요): 선택한 감량·대체를 내 맞춤 레시피로 저장 */
	@PostMapping("/{id}/adjust/save")
	public String save(@PathVariable("id") Long id,
					   @RequestParam(name = "goal") NutritionGoal goal,
					   @RequestParam(name = "option") String option,
					   Principal principal, RedirectAttributes redirect) {
		checkVisible(id, principal);
		boolean ok = savedRecipeService.save(principal.getName(), id, goal, option).isPresent();
		redirect.addFlashAttribute("savedMessage", ok ? "내 맞춤 레시피로 저장했습니다. 마이페이지에서 다시 볼 수 있습니다." : "저장할 후보를 찾을 수 없습니다.");
		return "redirect:/recipes/" + id + "/adjust?goal=" + goal.name() + "&option=" + java.net.URLEncoder.encode(option, StandardCharsets.UTF_8);
	}

	/** 레시피 CSV 다운로드. goal·option을 주면 감량·대체를 적용한 맞춤 레시피 */
	@GetMapping("/{id}/download")
	public ResponseEntity<byte[]> download(@PathVariable("id") Long id,
										   @RequestParam(name = "goal", required = false) NutritionGoal goal,
										   @RequestParam(name = "option", required = false) String option,
										   Principal principal) {
		checkVisible(id, principal);
		var export = exportService.export(id, goal, option)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "레시피를 찾을 수 없습니다."));
		return ResponseEntity.ok()
				.contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.attachment().filename(export.fileName(), StandardCharsets.UTF_8).build().toString())
				.body(export.content());
	}

	@GetMapping("/{id}")
	public String detail(@PathVariable("id") Long id, @RequestParam(name = "goal", required = false) NutritionGoal goal,
						 Principal principal, Model model) {
		checkVisible(id, principal);
		model.addAttribute("mine", principal != null && myRecipeService.owns(principal.getName(), id));
		model.addAttribute("selectedGoal", goal);
		model.addAttribute("goals", NutritionGoal.values());
		model.addAttribute("recipe", recipeService.detail(id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "레시피를 찾을 수 없습니다.")));
		return "recipes/detail";
	}
}
