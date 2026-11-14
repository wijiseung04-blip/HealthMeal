package com.springboot.controller;

import com.springboot.checkup.TestItem;
import com.springboot.dto.CheckupForm;
import com.springboot.service.HealthCheckupService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.security.Principal;
import java.util.Map;

/** 회원 전용: 건강검진 수치 확인·저장·결과 (매개변수 이름은 이클립스 빌드를 위해 명시) */
@Controller
@RequestMapping("/mypage/health")
@RequiredArgsConstructor
public class HealthController {

	private final HealthCheckupService checkupService;
	private final com.springboot.service.PersonalGoalService goalService;

	/** fileId가 있으면 그 PDF에서 수치를 추출해 채운 폼, 없으면 직접 입력 폼 */
	@GetMapping("/new")
	public String form(@RequestParam(name = "fileId", required = false) Long fileId, Principal principal, Model model) throws IOException {
		var prepared = checkupService.prepare(principal.getName(), fileId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "파일을 찾을 수 없습니다."));
		model.addAttribute("prepared", prepared);
		model.addAttribute("checkupForm", prepared.form());
		model.addAttribute("items", TestItem.values());
		model.addAttribute("errors", Map.of());
		return "health/form";
	}

	@PostMapping
	public String save(@ModelAttribute("checkupForm") CheckupForm form, Principal principal, Model model) {
		Map<String, String> errors = checkupService.validate(form);
		if (!errors.isEmpty()) {
			model.addAttribute("prepared", new HealthCheckupService.Prepared(form, form.getFileId() != null, true, form.getExtracted().size(), null));
			model.addAttribute("items", TestItem.values());
			model.addAttribute("errors", errors);
			return "health/form";
		}
		Long id = checkupService.save(principal.getName(), form);
		goalService.regenerate(principal.getName()); // 새 검진 결과로 목표를 다시 만든다 (직접 고친 목표는 유지)
		return "redirect:/mypage/health/" + id;
	}

	@GetMapping("/{id}")
	public String result(@PathVariable("id") Long id, Principal principal, Model model) {
		model.addAttribute("checkup", checkupService.detail(principal.getName(), id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "검진 결과를 찾을 수 없습니다.")));
		return "health/result";
	}

	@PostMapping("/{id}/delete")
	public String delete(@PathVariable("id") Long id, Principal principal, RedirectAttributes redirect) {
		redirect.addFlashAttribute("message", checkupService.delete(principal.getName(), id) ? "검진 결과를 삭제했습니다." : "검진 결과를 찾을 수 없습니다.");
		return "redirect:/mypage";
	}
}
