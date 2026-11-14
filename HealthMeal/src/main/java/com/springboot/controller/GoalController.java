package com.springboot.controller;

import com.springboot.dto.ProfileForm;
import com.springboot.goal.GoalRules;
import com.springboot.service.PersonalGoalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;

/** 회원 전용: 프로필과 개인 영양 목표 */
@Controller
@RequestMapping("/mypage")
@RequiredArgsConstructor
public class GoalController {

	private final PersonalGoalService service;

	@GetMapping("/profile")
	public String profileForm(Principal principal, Model model) {
		model.addAttribute("form", service.profileForm(principal.getName()).orElseGet(ProfileForm::new));
		model.addAttribute("activities", GoalRules.Activity.values());
		return "goals/profile";
	}

	@PostMapping("/profile")
	public String saveProfile(@Valid @ModelAttribute("form") ProfileForm form, BindingResult binding, Principal principal, Model model,
							  RedirectAttributes redirect) {
		if (binding.hasErrors()) {
			model.addAttribute("activities", GoalRules.Activity.values());
			return "goals/profile";
		}
		service.saveProfile(principal.getName(), form);
		// 열량이 바뀌면 검진으로 만든 목표 값도 바뀐다
		int n = service.regenerate(principal.getName());
		redirect.addFlashAttribute("message", "프로필을 저장했습니다." + (n > 0 ? " 검진 결과로 만든 목표도 새 기준으로 다시 계산했습니다." : ""));
		return "redirect:/mypage/goals";
	}

	@GetMapping("/goals")
	public String goals(Principal principal, Model model) {
		model.addAttribute("personal", service.personal(principal.getName()));
		model.addAttribute("nutrients", GoalRules.UNITS.keySet().stream().sorted().toList());
		model.addAttribute("units", GoalRules.UNITS);
		return "goals/goals";
	}

	@PostMapping("/goals/regenerate")
	public String regenerate(Principal principal, RedirectAttributes redirect) {
		int n = service.regenerate(principal.getName());
		redirect.addFlashAttribute("message", n > 0 ? "최근 검진 결과로 목표 " + n + "개를 만들었습니다."
				: "검진 결과에서 만들 목표가 없습니다. (검진 기록이 없거나 모든 항목이 정상이거나, 열량 관련 목표는 프로필이 필요합니다)");
		return "redirect:/mypage/goals";
	}

	@PostMapping("/goals")
	public String saveGoal(@RequestParam(name = "nutrient") String nutrient,
						   @RequestParam(name = "type") GoalRules.GoalType type,
						   @RequestParam(name = "min", required = false) Double min,
						   @RequestParam(name = "max", required = false) Double max,
						   @RequestParam(name = "priority", defaultValue = "1") int priority,
						   Principal principal, RedirectAttributes redirect) {
		String error = service.saveGoal(principal.getName(), nutrient, type, min, max, priority);
		redirect.addFlashAttribute(error == null ? "message" : "error", error == null ? nutrient + " 목표를 저장했습니다." : error);
		return "redirect:/mypage/goals";
	}

	@PostMapping("/goals/{id}/delete")
	public String deleteGoal(@PathVariable("id") Long id, Principal principal, RedirectAttributes redirect) {
		redirect.addFlashAttribute("message", service.deleteGoal(principal.getName(), id) ? "목표를 삭제했습니다." : "찾을 수 없습니다.");
		return "redirect:/mypage/goals";
	}
}
