package com.springboot.controller;

import com.springboot.domain.NutritionGoal;
import com.springboot.pipeline.MealItemLoader.Situation;
import com.springboot.service.MealPlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.security.Principal;

/** 맞춤형 식사 조합 추천 (비회원도 사용, 회원은 최근 검진의 성별·추천 목표가 기본값) */
@Controller
@RequestMapping("/meals")
@RequiredArgsConstructor
public class MealController {

	private final MealPlanService service;
	private final com.springboot.service.SavedMealService savedMealService;

	/** 회원 전용(보안 설정상 POST는 로그인 필요): 조합을 마이페이지에 저장하고 같은 화면으로 돌아간다 */
	@org.springframework.web.bind.annotation.PostMapping("/save")
	public String save(@RequestParam(name = "situation") Situation situation,
					   @RequestParam(name = "goal", required = false) String goal,
					   @RequestParam(name = "sex", required = false) String sex,
					   @RequestParam(name = "page", defaultValue = "0") int page,
					   @RequestParam(name = "items") java.util.List<Long> items,
					   Principal principal, org.springframework.web.servlet.mvc.support.RedirectAttributes redirect) {
		NutritionGoal g = goal == null || goal.isBlank() ? null : NutritionGoal.valueOf(goal);
		boolean ok = savedMealService.save(principal.getName(), situation, g, items).isPresent();
		redirect.addFlashAttribute("savedMessage", ok ? "마이페이지의 '저장한 식사 조합'에 저장했습니다." : "저장할 조합을 찾을 수 없습니다.");
		return "redirect:/meals?situation=" + situation.name() + "&goal=" + (g == null ? "" : g.name()) + "&sex=" + (sex == null ? "" : sex) + "&page=" + page;
	}

	@GetMapping
	public String meals(@RequestParam(name = "situation", defaultValue = "CONVENIENCE") Situation situation,
						@RequestParam(name = "goal", required = false) String goal,
						@RequestParam(name = "sex", required = false) String sex,
						@RequestParam(name = "page", defaultValue = "0") int page,
						Principal principal, Model model) {
		var defaults = service.defaults(principal == null ? null : principal.getName());
		// 처음 들어오면(goal 매개변수 없음) 검진 기본값, "균형"을 고르면 빈 값
		NutritionGoal chosenGoal = goal == null ? defaults.goal() : goal.isBlank() ? null : NutritionGoal.valueOf(goal);
		String chosenSex = sex == null ? defaults.sex() : sex.isBlank() ? null : sex;
		model.addAttribute("plan", service.plan(situation, chosenGoal, chosenSex, page));
		model.addAttribute("defaults", defaults);
		model.addAttribute("situations", Situation.values());
		model.addAttribute("goals", NutritionGoal.values());
		model.addAttribute("page", Math.max(page, 0));
		return "meals";
	}
}
